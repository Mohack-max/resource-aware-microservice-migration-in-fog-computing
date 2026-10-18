package org.fog.placement;

/**
 * A baseline inspired by MOFCO (Mahdizadeh, Oustad & Ansari, "MOFCO: Mobility- and Migration-Aware
 * Task Offloading in Three-Layer Fog Computing Environments", 2025, arXiv:2507.12028).
 *
 * MOFCO solves a different problem than this study (per-task cloud/fog/local offloading with
 * continuous resource allocation, evaluated on SUMO vehicular traces) rather than migrating an
 * already-placed, persistent microservice as modelled here. This class is not a line-for-line
 * reproduction; it is an adaptation of MOFCO's central mechanism to the microservice-migration
 * setting used in this study, so that the comparison is fair but the adaptation is honest about
 * not being an exact port. Two ideas are carried over:
 *
 *   1. Proactive, prediction-based migration avoidance. MOFCO predicts how long a user equipment
 *      will remain within a fog node's coverage (its "sojourn time", from position/velocity/
 *      direction, Eqs. 22-28 in the paper) and avoids starting work that is unlikely to finish, or
 *      unlikely to pay off, before the user leaves. Here, iFogSim2's mobility dataset already
 *      contains the full future handoff schedule (LocationHandler.getTimeSheet), so the predicted
 *      sojourn time is read directly from that schedule (the time until the next scheduled handoff)
 *      rather than re-derived kinematically -- a more accurate substitute available only because
 *      this simulator pre-generates the whole trace, which MOFCO's online setting does not assume.
 *   2. A cost comparison in the same spirit as MOFCO's weighted latency/energy objective (Eq. 20),
 *      reusing this study's existing alpha/beta/gamma weights for comparability with the other
 *      policies.
 *
 * Decision rule: migrate only if (a) the migration is predicted to complete before the user's
 * predicted sojourn time at the current attachment point runs out -- mirroring MOFCO's avoidance of
 * migrations that cannot pay off before the next handoff -- and (b) the weighted cost of migrating
 * is lower than the weighted cost of staying.
 */
public class MofcoInspiredPolicy implements MigrationPolicy {

    final double alpha, beta, gamma;

    public MofcoInspiredPolicy(double alpha, double beta, double gamma) {
        this.alpha = alpha; this.beta = beta; this.gamma = gamma;
    }

    public boolean shouldMigrate(Context c) {
        // No sojourn prediction available (e.g. end of trace): fall back to a plain cost comparison.
        if (c.predictedSojournTime <= 0)
            return (alpha * c.stayLatency) > (beta * c.migrationDelay + gamma * c.migrationEnergy);

        // Proactive avoidance: don't start a migration predicted to outlast the user's remaining
        // time at this attachment point, the direct analogue of MOFCO's sojourn-aware offloading
        // decision (a migration that can't complete in time cannot deliver the latency benefit it
        // was meant to).
        boolean feasible = c.migrationDelay < c.predictedSojournTime;
        boolean worthwhile = (alpha * c.stayLatency) > (beta * c.migrationDelay + gamma * c.migrationEnergy);
        return feasible && worthwhile;
    }

    public boolean reconsiderLeftBehind() { return true; }

    public String name() {
        return "mofco-inspired(a=" + alpha + ",b=" + beta + ",g=" + gamma + ")";
    }
}