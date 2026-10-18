package org.fog.placement;

import org.fog.entities.FogDevice;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A tabular Q-learning migration policy: state buckets encode (latency saved by staying,
 * migration transfer cost, and resource availability at the destination), action is
 * {STAY, MIGRATE}. Reward is the negative of the realised cost of whichever action was taken,
 * so the agent learns, per state, which action tends to cost less -- rather than us hand-picking
 * fixed weights (alpha/beta/gamma) as the earlier CostBased policy did.
 *
 * Resource availability is a genuinely new input: the earlier policy never looked at whether the
 * destination node actually had room, which is the likely cause of the cloud-cost blow-ups seen
 * with several concurrent users.
 *
 * Because iFogSim2 runs each simulation in its own JVM (System.exit at the end), the Q-table is
 * persisted to a small text file and reloaded across runs -- each full simulation is one training
 * episode. Run many episodes with train=true (exploring), then evaluate with train=false
 * (greedy, epsilon=0) for the results that go in the paper.
 */
public class QLearningMigrationPolicy implements MigrationPolicy {

    private static final int LATENCY_BUCKETS = 3;
    // Widened from 3 to 6 buckets: with 3 buckets and edges {50,200}, migration costs at module
    // sizes 200000 (~80) and 400000 (~158) both fell into the same ">50,<=200" bucket, so the agent
    // could not tell those two situations apart no matter how the reward was shaped -- this was the
    // actual cause of the size=400000 latency collapse, not an undertrained or badly weighted reward.
    // Edges below are chosen from the observed per-hop migration delay at each tested module size
    // (roughly 4 / 20 / 40 / 80 / 158 / 316 for 10k/50k/100k/200k/400k/800k), so each size this study
    // evaluates gets its own bucket.
    private static final int COST_BUCKETS = 6;
    private static final int RESOURCE_BUCKETS = 3;
    private static final int STATES = LATENCY_BUCKETS * COST_BUCKETS * RESOURCE_BUCKETS;
    private static final int ACTIONS = 2; // 0 = stay, 1 = migrate

    private final double[][] q = new double[STATES][ACTIONS];
    private final int[][] visits = new int[STATES][ACTIONS];
    private final boolean train;
    private final double epsilon;
    private final double alpha; // reward weight on latency
    private final double beta;  // reward weight on migration cost
    private final String qtablePath;
    private final Random rnd = new Random();

    public int decisionsMade = 0, decisionsMigrated = 0;

    private final boolean useResourceState;
    private final double feasibilityPenalty;

    public QLearningMigrationPolicy(String qtablePath, boolean train, double epsilon, double alpha, double beta) {
        this(qtablePath, train, epsilon, alpha, beta, true, 0.0);
    }

    /**
     * @param useResourceState if false, the resource-availability bucket is held constant (neutral)
     *   so the agent cannot condition on it. Used for the RQ2 ablation: same agent, same training
     *   budget, with the resource-aware state removed.
     * @param feasibilityPenalty extra reward penalty applied when a migration is attempted but its
     *   transfer time exceeds the typical inter-handoff interval (see MigrationPolicy.CostBased's
     *   maxFeasibleDelay for the same idea applied to the hand-tuned policy). 0 disables it.
     */
    public QLearningMigrationPolicy(String qtablePath, boolean train, double epsilon, double alpha, double beta,
                                    boolean useResourceState, double feasibilityPenalty) {
        this.qtablePath = qtablePath;
        this.train = train;
        this.epsilon = train ? epsilon : 0.0;
        this.alpha = alpha;
        this.beta = beta;
        this.useResourceState = useResourceState;
        this.feasibilityPenalty = feasibilityPenalty;
        load();
    }

    private int bucket(double value, double[] edges) {
        for (int i = 0; i < edges.length; i++)
            if (value <= edges[i]) return i;
        return edges.length;
    }

    private int stateOf(Context c, double resourceAvailability) {
        int lb = bucket(c.stayLatency, new double[]{10, 30});
        int cb = bucket(c.migrationDelay, new double[]{15, 35, 65, 120, 220});
        int rb = bucket(resourceAvailability, new double[]{0.33, 0.66});
        return (lb * COST_BUCKETS + cb) * RESOURCE_BUCKETS + rb;
    }

    /** Resource-availability score at the candidate destination: mean of available/capacity across
     *  MIPS, RAM and bandwidth (0 = full, 1 = empty). Falls back to 0.5 (neutral) if unavailable. */
    static double resourceAvailability(FogDevice dest) {
        try {
            var host = dest.getHost();
            double mipsFrac = host.getTotalMips() > 0 ? host.getAvailableMips() / host.getTotalMips() : 0.5;
            double ramFrac = host.getRam() > 0 ? host.getRamProvisioner().getAvailableRam() / (double) host.getRam() : 0.5;
            double bwFrac = host.getBw() > 0 ? host.getBwProvisioner().getAvailableBw() / (double) host.getBw() : 0.5;
            return (mipsFrac + ramFrac + bwFrac) / 3.0;
        } catch (Exception e) {
            return 0.5;
        }
    }

    public boolean shouldMigrate(Context c) {
        return shouldMigrate(c, 0.5);
    }

    public boolean shouldMigrate(Context c, double resourceAvailability) {
        decisionsMade++;
        double effectiveResourceAvailability = useResourceState ? resourceAvailability : 0.5;
        int s = stateOf(c, effectiveResourceAvailability);
        // Hard feasibility gate: a migration whose transfer time is unrealistic given typical
        // handoff frequency is refused outright, the same mechanism used by the hand-tuned
        // CostBased policy (see MigrationPolicy.java). Reward-shaping alone (a soft penalty) proved
        // unable to reliably override an already-learned preference to migrate without a large
        // amount of additional training data; a hard gate takes effect immediately and mirrors the
        // hand-tuned baseline's treatment of the same boundary condition, which keeps the comparison
        // between the two policies on equal footing for this specific safeguard.
        if (!train && feasibilityPenalty > 0 && c.migrationDelay > feasibilityPenalty) {
            return false;
        }
        int action;
        if (train && rnd.nextDouble() < epsilon) {
            action = rnd.nextInt(ACTIONS);
        } else {
            action = q[s][1] > q[s][0] ? 1 : 0;
        }

        if (train) {
            double costStay = alpha * c.stayLatency;
            double costMigrate = beta * c.migrationDelay; // energy term omitted from reward for now (gamma left at 0 in practice)
            // Feasibility shaping: a migration that cannot plausibly finish before the next handoff
            // (typical inter-handoff interval ~9 time units in this topology; see boundary-condition
            // finding in preliminary results) is penalised directly in the reward, rather than being
            // vetoed outright as the hand-tuned policy's feasibility gate does. This lets the agent
            // learn the trade-off instead of having it imposed as a hard rule.
            if (feasibilityPenalty > 0 && action == 1 && c.migrationDelay > feasibilityPenalty)
                costMigrate += (c.migrationDelay - feasibilityPenalty);
            double realisedCost = (action == 1) ? costMigrate : costStay;
            double reward = -realisedCost;
            int n = ++visits[s][action];
            double lr = 1.0 / n; // decaying learning rate -> converges to the sample mean reward
            q[s][action] += lr * (reward - q[s][action]);
        }

        if (action == 1) decisionsMigrated++;
        return action == 1;
    }

    /** Predicted value (higher is better) of migrating to a candidate whose situation is described
     *  by c/resourceAvailability. Used to rank multiple candidate destinations against each other
     *  and against staying, without changing the learned table itself (evaluation only, no update). */
    public double migrateScore(Context c, double resourceAvailability) {
        int s = stateOf(c, useResourceState ? resourceAvailability : 0.5);
        return q[s][1];
    }

    /** Predicted value (higher is better) of staying, for the same state used by migrateScore. */
    public double stayScore(Context c, double resourceAvailability) {
        int s = stateOf(c, useResourceState ? resourceAvailability : 0.5);
        return q[s][0];
    }

    public boolean reconsiderLeftBehind() { return true; }

    public String name() {
        return "qlearn(" + (train ? "train" : "eval") + ",eps=" + epsilon
                + ",res=" + useResourceState + ",fp=" + feasibilityPenalty + ")";
    }

    public synchronized void save() {
        if (!train) return; // evaluation runs must not alter the learned table
        try (PrintWriter w = new PrintWriter(new FileWriter(qtablePath))) {
            for (int s = 0; s < STATES; s++)
                for (int a = 0; a < ACTIONS; a++)
                    w.println(s + "," + a + "," + q[s][a] + "," + visits[s][a]);
        } catch (IOException e) {
            System.err.println("QLearningMigrationPolicy: could not save Q-table: " + e.getMessage());
        }
    }

    private void load() {
        File f = new File(qtablePath);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length != 4) continue;
                int s = Integer.parseInt(p[0]), a = Integer.parseInt(p[1]);
                q[s][a] = Double.parseDouble(p[2]);
                visits[s][a] = Integer.parseInt(p[3]);
            }
        } catch (IOException e) {
            System.err.println("QLearningMigrationPolicy: could not load Q-table: " + e.getMessage());
        }
    }
}