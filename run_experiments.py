#!/usr/bin/env python3
"""
Batch runner for MobilityExperiment (iFogSim2 mobility-aware migration study).

Each simulation runs in its own JVM (iFogSim2 calls System.exit and keeps static
counters, so runs cannot share a process). Every run appends one row to a CSV file;
a summary with mean and 95% confidence interval per configuration is printed and
written next to it.

Example (run from the iFogSim2 project folder):
    python run_experiments.py --reps 10 --users 3 --sizes 10000 200000 800000 ^
        --policies always never "cost:1,1,0,0,10"

Policy syntax:  always | never | cost:alpha,beta,gamma,theta,horizon
"""
import argparse
import csv
import glob
import math
import os
import re
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

MAIN_CLASS = "org.fog.test.perfeval.MobilityExperiment"

# Idle power (W) taken from createFogDevice(...) in the example; used to split idle vs dynamic energy.
IDLE_POWER = {"cloud": 16 * 83.25, "mobile": 82.44, "fog": 83.4333}

FIELDS = ["run_id", "timestamp", "policy", "users", "size", "rep", "exit_code", "wall_s",
          "loop_latency", "cloud_cost", "network_usage", "migration_time",
          "migrations_performed", "migrations_skipped", "decision_ms",
          "energy_total", "energy_fog_dynamic", "energy_cloud_dynamic", "energy_mobile_dynamic", "error"]

METRICS = ["loop_latency", "cloud_cost", "network_usage", "migration_time",
           "migrations_performed", "migrations_skipped", "decision_ms",
           "energy_fog_dynamic", "energy_cloud_dynamic"]

T_CRIT_95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262,
             10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131, 16: 2.120, 17: 2.110,
             18: 2.101, 19: 2.093, 20: 2.086, 25: 2.060, 29: 2.045, 30: 2.042}


def t_crit(df):
    if df <= 0:
        return float("nan")
    for k in sorted(T_CRIT_95):
        if df <= k:
            return T_CRIT_95[k]
    return 1.96


def build_classpath(root, classes, jars):
    entries = [classes]
    entries += glob.glob(os.path.join(jars, "**", "*.jar"), recursive=True)
    return os.pathsep.join(entries)


def policy_args(spec):
    if spec in ("always", "never"):
        return [spec]
    if spec.startswith("cost"):
        # cost:alpha,beta,gamma,theta,horizon[,maxFeasibleDelay]
        # maxFeasibleDelay: skip a migration outright if its transfer time exceeds this many
        # simulation time units (0 = no gate). Default 400 was found to help at large module sizes
        # without over-suppressing migration at moderate sizes -- see MigrationPolicy.java.
        params = spec.split(":", 1)[1] if ":" in spec else "1,1,0,0,1,400"
        parts = params.split(",")
        if len(parts) == 5:
            parts.append("400")
        a, b, g, th, h, cap = parts
        return ["cost", a, b, g, th, h, cap]
    if spec.startswith("mofco"):
        # mofco:alpha,beta,gamma
        params = spec.split(":", 1)[1] if ":" in spec else "1,1,0"
        a, b, g = (params.split(",") + ["1", "1", "0"])[:3]
        return ["mofco", a, b, g]
    if spec.startswith("qlearn"):
        # qlearn:qtablePath,train(true|false)[,epsilon,alpha,beta,useResourceState,feasibilityPenalty]
        defaults = ["qtable.txt", "false", "0.15", "1", "1", "true", "300"]
        params = spec.split(":", 1)[1] if ":" in spec else ",".join(defaults)
        parts = params.split(",")
        parts = parts + defaults[len(parts):]
        path, train, eps, a, b, useRes, feasPen = parts[:7]
        return ["qlearn", path, train, eps, a, b, useRes, feasPen]
    raise SystemExit("unknown policy spec: " + spec)


def num(pattern, text):
    m = re.search(pattern, text, re.MULTILINE)
    if not m:
        return float("nan")
    try:
        return float(m.group(1))
    except ValueError:
        return float("nan")  # e.g. "null"


def parse_output(text, sim_time):
    r = {
        "loop_latency": num(r"^\[M-SENSOR.*?\]\s+--->\s+(\S+)", text),
        "cloud_cost": num(r"^Cost of execution in cloud = (\S+)", text),
        "network_usage": num(r"^Total network usage = (\S+)", text),
        "migration_time": num(r"^Total time required for module migration = (\S+)", text),
        "migrations_performed": num(r"^Migrations performed = (\S+)", text),
        "migrations_skipped": num(r"^Migrations skipped = (\S+)", text),
        "decision_ms": num(r"^Decision runtime \(ms\) = (\S+)", text),
    }
    total = fog_dyn = cloud_dyn = mob_dyn = 0.0
    found = False
    for name, val in re.findall(r"^(\S+) : Energy Consumed = (\S+)$", text, re.MULTILINE):
        e = float(val)
        found = True
        total += e
        if name == "cloud":
            cloud_dyn += e - IDLE_POWER["cloud"] * sim_time
        elif name.startswith("mobile"):
            mob_dyn += e - IDLE_POWER["mobile"] * sim_time
        else:
            fog_dyn += e - IDLE_POWER["fog"] * sim_time
    r["energy_total"] = total if found else float("nan")
    r["energy_fog_dynamic"] = fog_dyn if found else float("nan")
    r["energy_cloud_dynamic"] = cloud_dyn if found else float("nan")
    r["energy_mobile_dynamic"] = mob_dyn if found else float("nan")
    return r


def one_run(job, cfg):
    run_id, spec, users, size, rep = job
    args = policy_args(spec)
    if args[0] == "qlearn" and not os.path.isabs(args[1]):
        args[1] = os.path.join(cfg.root, args[1])
    cmd = [cfg.java, "-cp", cfg.classpath, MAIN_CLASS, str(users), str(size), str(cfg.extended)] + args
    t0 = time.time()
    row = {"run_id": run_id, "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"), "policy": spec,
           "users": users, "size": size, "rep": rep, "error": ""}
    try:
        p = subprocess.run(cmd, cwd=cfg.root, capture_output=True, text=True, errors="replace", timeout=cfg.timeout)
        text = p.stdout
        row["exit_code"] = p.returncode
        row.update(parse_output(text, cfg.sim_time))
        if p.returncode != 0 or "Exception" in p.stderr:
            lines = [l for l in p.stderr.strip().splitlines() if l.strip()] or ["non-zero exit"]
            row["error"] = " | ".join(lines[:3])[:400]
            row["stderr"] = p.stderr
    except subprocess.TimeoutExpired:
        row["exit_code"] = -1
        row["error"] = "timeout"
    row["wall_s"] = round(time.time() - t0, 1)
    return row


def summarise(rows, out_path):
    groups = defaultdict(list)
    for r in rows:
        if r.get("exit_code") == 0:
            groups[(r["policy"], r["users"], r["size"])].append(r)
    summary = []
    for key in sorted(groups):
        g = groups[key]
        line = {"policy": key[0], "users": key[1], "size": key[2], "n": len(g)}
        for m in METRICS:
            vals = [x[m] for x in g if isinstance(x.get(m), float) and not math.isnan(x[m])]
            if vals:
                mean = statistics.mean(vals)
                sd = statistics.stdev(vals) if len(vals) > 1 else float("nan")
                half = t_crit(len(vals) - 1) * sd / math.sqrt(len(vals)) if len(vals) > 1 else float("nan")
            else:
                mean = sd = half = float("nan")
            line[m + "_mean"] = mean
            line[m + "_ci95"] = half
        summary.append(line)
    if summary:
        with open(out_path, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(summary[0].keys()))
            w.writeheader()
            w.writerows(summary)
    return summary


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="iFogSim2 project folder (working directory for each run)")
    ap.add_argument("--classes", default=None, help="compiled classes folder (default: auto-detect)")
    ap.add_argument("--jars", default=None, help="jars folder (default: <root>/jars)")
    ap.add_argument("--java", default="java")
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--users", type=int, nargs="+", default=[3])
    ap.add_argument("--sizes", type=int, nargs="+", default=[10000, 200000, 800000])
    ap.add_argument("--policies", nargs="+", default=["always", "never", "cost:1,1,0,0,10"])
    ap.add_argument("--extended", default="true", help="true = 4-microservice app, false = original 3 modules")
    ap.add_argument("--sim-time", type=float, default=2000, help="Config.MAX_SIMULATION_TIME")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--out", default="results/results.csv")
    cfg = ap.parse_args()

    cfg.root = os.path.abspath(cfg.root)
    cands = [cfg.classes] if cfg.classes else [
        os.path.join(cfg.root, "out", "production", "iFogSim2"),
        os.path.join(cfg.root, "out", "production", os.path.basename(cfg.root)),
        os.path.join(cfg.root, "out")]
    classes = next((c for c in cands if c and os.path.isdir(c)), None)
    if not classes or not glob.glob(os.path.join(classes, "**", "MobilityExperiment.class"), recursive=True):
        sys.exit("Could not find compiled MobilityExperiment.class. Build the project in IntelliJ first, "
                 "or pass --classes <folder>.")
    jars = cfg.jars or os.path.join(cfg.root, "jars")
    cfg.classpath = build_classpath(cfg.root, os.path.abspath(classes), os.path.abspath(jars))

    out_path = cfg.out if os.path.isabs(cfg.out) else os.path.join(cfg.root, cfg.out)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    jobs, i = [], 0
    for users in cfg.users:
        for size in cfg.sizes:
            for spec in cfg.policies:
                for rep in range(1, cfg.reps + 1):
                    i += 1
                    jobs.append((i, spec, users, size, rep))
    print("%d runs (%d configs x %d reps), %d parallel" % (len(jobs), len(jobs) // cfg.reps, cfg.reps, cfg.workers))

    new_file = not os.path.exists(out_path)
    rows = []
    with open(out_path, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        if new_file:
            w.writeheader()
        # first run alone: it may create random-mobility dataset files that parallel runs would race on
        first = one_run(jobs[0], cfg)
        rows.append(first)
        w.writerow(first)
        f.flush()
        print("run 1/%d done (%s, %.1fs) %s" % (len(jobs), first["policy"], first["wall_s"], first["error"]))
        if first.get("exit_code") != 0:
            print("\nThe first run failed, so I stopped. Full error output:\n" + first.get("stderr", "(none)"))
            print("Command used:\n" + " ".join([cfg.java, "-cp", "<classpath>", MAIN_CLASS]))
            print("Java in use:")
            subprocess.run([cfg.java, "-version"])
            sys.exit(1)
        with ThreadPoolExecutor(max_workers=cfg.workers) as ex:
            for n, row in enumerate(ex.map(lambda j: one_run(j, cfg), jobs[1:]), start=2):
                rows.append(row)
                w.writerow(row)
                f.flush()
                print("run %d/%d done (%s users=%s size=%s, %.1fs) %s" %
                      (n, len(jobs), row["policy"], row["users"], row["size"], row["wall_s"], row["error"]))

    summary_path = os.path.splitext(out_path)[0] + "_summary.csv"
    summary = summarise(rows, summary_path)
    failed = [r for r in rows if r.get("exit_code") != 0]
    print("\n%d/%d runs succeeded. Raw: %s\nSummary: %s" % (len(rows) - len(failed), len(rows), out_path, summary_path))
    for s in summary:
        print("%-28s users=%s size=%-7s n=%d  latency=%.1f +/-%.1f  migrations=%.0f  migTime=%.0f  fogDynEnergy=%.0f" % (
            s["policy"], s["users"], s["size"], s["n"], s["loop_latency_mean"], s["loop_latency_ci95"],
            s["migrations_performed_mean"], s["migration_time_mean"], s["energy_fog_dynamic_mean"]))


if __name__ == "__main__":
    main()