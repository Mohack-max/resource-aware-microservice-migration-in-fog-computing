package org.fog.placement;

/**
 * Pluggable decision rule: should this microservice follow the user to the new parent gateway?
 * Called once per (mobility event, application, candidate microservice) by
 * MicroservicesMobilityClusteringController.
 *
 * All time quantities use iFogSim's simulation time unit (the same unit as uplink latencies
 * and bandwidth-based transfer delays), so no unit conversion is needed inside a policy.
 */
public interface MigrationPolicy {

    boolean shouldMigrate(Context ctx);

    String name();

    /**
     * If true, microservices left behind at earlier handoffs are offered to the policy again at every
     * handoff. The baselines keep the original iFogSim2 candidate set (false).
     */
    default boolean reconsiderLeftBehind() { return false; }

    /** Everything a policy may look at for one candidate microservice. */
    class Context {
        public String mobileName, application, module;
        public int fromDeviceId, toDeviceId;
        public double moduleSize;
        /** Network latency between the user's new parent and the module if it STAYS where it is. */
        public double stayLatency;
        /** Time to transfer the module (up + down delay) if it MIGRATES. */
        public double migrationDelay;
        /** Extra energy (busy minus idle power, times migration delay) at the receiving node. */
        public double migrationEnergy;
        /** Time until this mobile's next scheduled handoff (0/negative if unknown), used by the
         *  MOFCO-inspired policy's proactive feasibility check. Other policies may ignore it. */
        public double predictedSojournTime = 0;
    }

    /** Baseline: iFogSim2's default behaviour, migrate on every handoff. */
    class Always implements MigrationPolicy {
        public boolean shouldMigrate(Context c) { return true; }
        public String name() { return "always"; }
    }

    /** Baseline: modules are never moved after initial placement. */
    class Never implements MigrationPolicy {
        public boolean shouldMigrate(Context c) { return false; }
        public String name() { return "never"; }
    }

    /**
     * Proposed policy v1: migrate only if the projected saving exceeds a threshold.
     * C_stay = alpha * L_stay * H ; C_move = beta * M + gamma * E ; migrate if C_stay - C_move > theta.
     * H = horizon: number of tuples over which the latency saving is amortised (expected dwell time
     * at the new gateway divided by the tuple inter-arrival time).
     * (L after migration is 0 because the module is co-located with the user's new parent.)
     */
    class CostBased implements MigrationPolicy {
        final double alpha, beta, gamma, theta, horizon, maxFeasibleDelay;
        /**
         * @param maxFeasibleDelay if > 0, a hard feasibility gate: a migration whose transfer time
         *   (Context.migrationDelay) exceeds this is skipped outright, regardless of the cost formula.
         *   This guards against attempting to migrate a module that cannot arrive before the user has
         *   already moved on again (a "chasing a moving target" migration, which wastes bandwidth and
         *   time without ever delivering the intended latency benefit). Pass 0 to disable the gate.
         *   A natural setting is a small multiple of the typical time between handoffs.
         */
        public CostBased(double alpha, double beta, double gamma, double theta, double horizon, double maxFeasibleDelay) {
            this.alpha = alpha; this.beta = beta; this.gamma = gamma; this.theta = theta; this.horizon = horizon;
            this.maxFeasibleDelay = maxFeasibleDelay;
        }
        public boolean shouldMigrate(Context c) {
            if (maxFeasibleDelay > 0 && c.migrationDelay > maxFeasibleDelay)
                return false;
            double stay = alpha * c.stayLatency * horizon;
            double move = beta * c.migrationDelay + gamma * c.migrationEnergy;
            return (stay - move) > theta;
        }
        public boolean reconsiderLeftBehind() { return true; }
        public String name() { return "cost(a=" + alpha + ",b=" + beta + ",g=" + gamma + ",theta=" + theta + ",H=" + horizon + ",cap=" + maxFeasibleDelay + ")"; }
    }

    static MigrationPolicy create(String kind, double a, double b, double g, double theta, double horizon, double maxFeasibleDelay) {
        switch (kind) {
            case "always": return new Always();
            case "never":  return new Never();
            case "cost":   return new CostBased(a, b, g, theta, horizon, maxFeasibleDelay);
            default: throw new IllegalArgumentException("unknown policy: " + kind);
        }
    }
}