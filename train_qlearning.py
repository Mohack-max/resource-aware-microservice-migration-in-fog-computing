#!/usr/bin/env python3
"""
Trains the Q-learning migration policy (QLearningMigrationPolicy) by running many short
simulation episodes, each one updating a shared Q-table file on disk.

Must run sequentially (one JVM at a time) -- each episode reads the current Q-table, updates
it based on what happened, and overwrites the file. Running episodes in parallel would let
them race on that file and corrupt the learned table, so this script does not use --workers.

Each episode randomly picks a user count and module size (from the ranges you plan to
evaluate on), so the learned table generalizes across the whole experiment grid rather than
just the one scenario it happened to train on.

Example (run from the iFogSim2 project folder):
    python train_qlearning.py --java "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe" ^
        --episodes 300 --users 1 3 6 10 --sizes 10000 50000 100000 200000 400000 800000 ^
        --qtable qtable.txt
"""
import argparse
import os
import random
import subprocess
import sys
import time

MAIN_CLASS = "org.fog.test.perfeval.MobilityExperiment"


def find_classes(root, explicit):
    if explicit:
        return explicit
    for c in [os.path.join(root, "out", "production", "iFogSim2"),
              os.path.join(root, "out", "production", os.path.basename(root)),
              os.path.join(root, "out")]:
        if os.path.isdir(c):
            return c
    sys.exit("Could not find compiled classes. Build the project in IntelliJ first, or pass --classes.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--classes", default=None)
    ap.add_argument("--jars", default=None)
    ap.add_argument("--java", default="java")
    ap.add_argument("--episodes", type=int, default=300)
    ap.add_argument("--users", type=int, nargs="+", default=[1, 3, 6, 10])
    ap.add_argument("--sizes", type=int, nargs="+", default=[10000, 50000, 100000, 200000, 400000, 800000])
    ap.add_argument("--extended", default="true")
    ap.add_argument("--epsilon-start", type=float, default=0.3)
    ap.add_argument("--epsilon-end", type=float, default=0.05)
    ap.add_argument("--alpha", type=float, default=1.0, help="reward weight on latency (not the RL learning rate)")
    ap.add_argument("--beta", type=float, default=1.0, help="reward weight on migration cost")
    ap.add_argument("--use-resource-state", default="true", choices=["true", "false"],
                     help="set to false to train the RQ2 ablation agent (same setup, resource-availability input disabled)")
    ap.add_argument("--feasibility-penalty", type=float, default=300.0,
                     help="reward penalty threshold for migrations unlikely to finish before the next handoff (0 disables)")
    ap.add_argument("--qtable", default="qtable.txt")
    ap.add_argument("--timeout", type=int, default=300)
    cfg = ap.parse_args()

    cfg.root = os.path.abspath(cfg.root)
    classes = os.path.abspath(find_classes(cfg.root, cfg.classes))
    jars = os.path.abspath(cfg.jars or os.path.join(cfg.root, "jars"))
    import glob
    classpath = os.pathsep.join([classes] + glob.glob(os.path.join(jars, "**", "*.jar"), recursive=True))
    qtable_path = cfg.qtable if os.path.isabs(cfg.qtable) else os.path.join(cfg.root, cfg.qtable)

    print("Training for %d episodes. Q-table: %s" % (cfg.episodes, qtable_path))
    print("(This runs one simulation at a time on purpose -- do not run another training job "
          "against the same Q-table file concurrently.)")

    t_start = time.time()
    fails = 0
    for ep in range(1, cfg.episodes + 1):
        eps = cfg.epsilon_start + (cfg.epsilon_end - cfg.epsilon_start) * (ep - 1) / max(1, cfg.episodes - 1)
        users = random.choice(cfg.users)
        size = random.choice(cfg.sizes)
        cmd = [cfg.java, "-cp", classpath, MAIN_CLASS, str(users), str(size), str(cfg.extended),
               "qlearn", qtable_path, "true", str(round(eps, 4)), str(cfg.alpha), str(cfg.beta),
               cfg.use_resource_state, str(cfg.feasibility_penalty)]
        t0 = time.time()
        try:
            p = subprocess.run(cmd, cwd=cfg.root, capture_output=True, text=True, timeout=cfg.timeout)
            ok = p.returncode == 0
        except subprocess.TimeoutExpired:
            ok = False
        if not ok:
            fails += 1
        if ep % 10 == 0 or ep == cfg.episodes:
            elapsed = time.time() - t_start
            print("episode %d/%d  (users=%d size=%d eps=%.3f)  %.1fs elapsed, %d failed so far" %
                  (ep, cfg.episodes, users, size, eps, elapsed, fails))

    print("\nTraining done in %.1fs (%d/%d episodes succeeded)." % (time.time() - t_start, cfg.episodes - fails, cfg.episodes))
    print("Q-table saved at: %s" % qtable_path)
    print('Now evaluate it (train=false, safe to run in parallel) with, e.g.:')
    print('  python run_experiments.py --java "%s" --reps 20 --users %s --sizes %s '
          '--policies "qlearn:%s,false,0,%s,%s,%s,%s" always never "cost:1,1,0,0,1,400"'
          % (cfg.java, " ".join(map(str, cfg.users)), " ".join(map(str, cfg.sizes)), cfg.qtable,
             cfg.alpha, cfg.beta, cfg.use_resource_state, cfg.feasibility_penalty))


if __name__ == "__main__":
    main()