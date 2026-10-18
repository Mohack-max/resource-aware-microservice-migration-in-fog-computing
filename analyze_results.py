#!/usr/bin/env python3
"""
Turns results.csv (from run_experiments.py) into the figures and tables the paper needs.

Usage:
    python analyze_results.py results/results.csv --out figures/

Produces, in --out:
  - latency_vs_size.png       : the real version of the proposal's illustrative Figure 3,
                                 one line per policy, latency vs module size (at a fixed user count)
  - latency_vs_users.png      : latency vs user count (RQ4 scalability), one line per policy
  - migration_tradeoff.png    : migrations performed vs latency, one point per (policy, config) -- the
                                 RQ3 trade-off scatter
  - ablation_comparison.png   : qlearn with vs without resource state, if both are present in the data
  - summary_table.tex         : a LaTeX table (booktabs) of the full results, ready to paste into the paper
"""
import argparse
import csv
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

POLICY_LABELS = {
    "always": "Always migrate",
    "never": "Never migrate",
}


def policy_label(spec):
    if spec in POLICY_LABELS:
        return POLICY_LABELS[spec]
    if spec.startswith("cost"):
        return "Cost-tuned (hand-tuned)"
    if spec.startswith("qlearn"):
        parts = spec.split(":", 1)[1].split(",") if ":" in spec else []
        use_res = parts[5] if len(parts) > 5 else "true"
        return "Learned (no resource state)" if use_res == "false" else "Learned (proposed)"
    return spec


def load(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            if r.get("exit_code") not in ("0", 0):
                continue
            try:
                r["users"] = int(r["users"])
                r["size"] = int(r["size"])
                r["loop_latency"] = float(r["loop_latency"])
                r["migrations_performed"] = float(r["migrations_performed"])
                r["energy_fog_dynamic"] = float(r["energy_fog_dynamic"])
            except (ValueError, KeyError):
                continue
            if r["loop_latency"] != r["loop_latency"]:  # NaN
                continue
            rows.append(r)
    if not rows:
        raise SystemExit("No usable rows found in %s (check the file has succeeded runs)." % path)
    return rows


def mean(vals):
    vals = list(vals)
    return sum(vals) / len(vals) if vals else float("nan")


def group_mean(rows, keys, metric):
    g = defaultdict(list)
    for r in rows:
        g[tuple(r[k] for k in keys)].append(r[metric])
    return {k: mean(v) for k, v in g.items()}


def plot_vs(rows, x_key, fixed_key, fixed_value, metric, ylabel, title, out_path):
    policies = sorted(set(r["policy"] for r in rows), key=lambda p: policy_label(p))
    fig, ax = plt.subplots(figsize=(7, 4.5))
    any_line = False
    for spec in policies:
        sub = [r for r in rows if r["policy"] == spec and r[fixed_key] == fixed_value]
        if not sub:
            continue
        by_x = group_mean(sub, [x_key], metric)
        xs = sorted(by_x, key=lambda t: t[0])
        if not xs:
            continue
        ax.plot([x[0] for x in xs], [by_x[x] for x in xs], marker="o", label=policy_label(spec))
        any_line = True
    if not any_line:
        plt.close(fig)
        return False
    ax.set_xlabel(x_key.replace("_", " ").title())
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    return True


def plot_tradeoff(rows, out_path):
    policies = sorted(set(r["policy"] for r in rows), key=lambda p: policy_label(p))
    fig, ax = plt.subplots(figsize=(7, 4.5))
    for spec in policies:
        sub = [r for r in rows if r["policy"] == spec]
        by_cfg = defaultdict(list)
        for r in sub:
            by_cfg[(r["users"], r["size"])].append(r)
        xs, ys = [], []
        for cfg, cfg_rows in by_cfg.items():
            xs.append(mean(r["migrations_performed"] for r in cfg_rows))
            ys.append(mean(r["loop_latency"] for r in cfg_rows))
        if xs:
            ax.scatter(xs, ys, label=policy_label(spec), s=40, alpha=0.75)
    ax.set_xlabel("Migrations performed (mean per configuration)")
    ax.set_ylabel("End-to-end latency (mean, ms)")
    ax.set_title("Migration aggressiveness vs. latency (RQ3)")
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def write_summary_table(rows, out_path):
    keys = ["policy", "users", "size"]
    groups = defaultdict(list)
    for r in rows:
        groups[tuple(r[k] for k in keys)].append(r)
    lines = [
        r"\begin{table}[h]", r"\centering",
        r"\begin{tabular}{@{}lrrrrr@{}}", r"\toprule",
        r"Policy & Users & Size & Latency (ms) & Migrations & Fog energy \\", r"\midrule",
    ]
    for key in sorted(groups):
        g = groups[key]
        lat = mean(r["loop_latency"] for r in g)
        mig = mean(r["migrations_performed"] for r in g)
        en = mean(r["energy_fog_dynamic"] for r in g)
        lines.append(r"%s & %d & %d & %.1f & %.0f & %.0f \\" %
                      (policy_label(key[0]).replace("_", r"\_"), key[1], key[2], lat, mig, en))
    lines += [r"\bottomrule", r"\end{tabular}",
              r"\caption{Mean results across repetitions for each policy and configuration.}",
              r"\label{tab:results}", r"\end{table}"]
    with open(out_path, "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results_csv")
    ap.add_argument("--out", default="figures")
    ap.add_argument("--fixed-users", type=int, default=None, help="user count to hold fixed for the vs-size plot (default: the most common value in the data)")
    ap.add_argument("--fixed-size", type=int, default=None, help="module size to hold fixed for the vs-users plot (default: the most common value in the data)")
    args = ap.parse_args()

    rows = load(args.results_csv)
    os.makedirs(args.out, exist_ok=True)

    users_seen = [r["users"] for r in rows]
    sizes_seen = [r["size"] for r in rows]
    fixed_users = args.fixed_users or max(set(users_seen), key=users_seen.count)
    fixed_size = args.fixed_size or max(set(sizes_seen), key=sizes_seen.count)

    made = []
    if plot_vs(rows, "size", "users", fixed_users, "loop_latency", "End-to-end latency (ms)",
               "Latency vs. microservice size (users=%d)" % fixed_users,
               os.path.join(args.out, "latency_vs_size.png")):
        made.append("latency_vs_size.png")
    if plot_vs(rows, "users", "size", fixed_size, "loop_latency", "End-to-end latency (ms)",
               "Latency vs. user count (size=%d)" % fixed_size,
               os.path.join(args.out, "latency_vs_users.png")):
        made.append("latency_vs_users.png")
    plot_tradeoff(rows, os.path.join(args.out, "migration_tradeoff.png"))
    made.append("migration_tradeoff.png")

    ablation_rows = [r for r in rows if r["policy"].startswith("qlearn")]
    if len({r["policy"] for r in ablation_rows}) > 1:
        if plot_vs(ablation_rows, "size", "users", fixed_users, "loop_latency", "End-to-end latency (ms)",
                   "Ablation: resource-aware state vs. not (users=%d)" % fixed_users,
                   os.path.join(args.out, "ablation_comparison.png")):
            made.append("ablation_comparison.png")

    write_summary_table(rows, os.path.join(args.out, "summary_table.tex"))
    made.append("summary_table.tex")

    print("Wrote to %s:" % args.out)
    for m in made:
        print("  " + m)


if __name__ == "__main__":
    main()