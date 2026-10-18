#!/usr/bin/env python3
"""
Aggregates a multi-seed evaluation (results from run_experiments.py where several independently
trained Q-tables are evaluated) into a seed-aware comparison between two policy variants.

Why this exists: averaging all repetitions from all seeds together understates how much the
result actually depends on which training run produced the table, since it blends two different
sources of noise (simulation randomness within a seed, and training randomness between seeds)
into one number. This script keeps them separate: it computes each seed's own mean first, then
reports the mean and 95% CI *across those per-seed means*, which is the methodologically honest
way to ask "does this effect hold up across independent training runs, not just within one."

Usage:
    python aggregate_seeds.py results/multiseed.csv --variant-a-prefix qtable_seed \
        --variant-b-prefix qtable_noRes_seed --out seed_comparison.csv

The policy column in results.csv for a qlearn spec looks like "qlearn:qtable_seed3.txt,false,...".
--variant-a-prefix / --variant-b-prefix match against the qtable filename (without .txt) to decide
which seed and which variant a row belongs to; adjust them if your filenames differ.
"""
import argparse
import csv
import math
import re
from collections import defaultdict

T_CRIT_95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}


def t_crit(df):
    return T_CRIT_95.get(df, 1.96 if df > 10 else 12.706)


def mean(vals):
    vals = list(vals)
    return sum(vals) / len(vals) if vals else float("nan")


def stdev(vals):
    vals = list(vals)
    if len(vals) < 2:
        return float("nan")
    m = mean(vals)
    return math.sqrt(sum((v - m) ** 2 for v in vals) / (len(vals) - 1))


def extract_seed(policy_field, prefix):
    m = re.search(r"qlearn:" + re.escape(prefix) + r"(\d+)\.txt", policy_field)
    return m.group(1) if m else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results_csv")
    ap.add_argument("--variant-a-prefix", default="qtable_seed", help="filename prefix (before the seed number) for variant A, e.g. the resource-aware agent")
    ap.add_argument("--variant-b-prefix", default="qtable_noRes_seed", help="filename prefix for variant B, e.g. the no-resource-state ablation")
    ap.add_argument("--variant-a-label", default="Resource-aware (proposed)")
    ap.add_argument("--variant-b-label", default="No resource state (ablation)")
    ap.add_argument("--metric", default="loop_latency", choices=["loop_latency", "energy_fog_dynamic", "migrations_performed"])
    ap.add_argument("--out", default="seed_comparison.csv")
    args = ap.parse_args()

    rows = []
    with open(args.results_csv, newline="") as f:
        for r in csv.DictReader(f):
            if r.get("exit_code") not in ("0", 0):
                continue
            try:
                r["users"] = int(r["users"]); r["size"] = int(r["size"])
                r[args.metric] = float(r[args.metric])
            except (ValueError, KeyError):
                continue
            if r[args.metric] != r[args.metric]:  # NaN
                continue
            seed_a = extract_seed(r["policy"], args.variant_a_prefix)
            seed_b = extract_seed(r["policy"], args.variant_b_prefix) if seed_a is None else None
            if seed_a:
                r["_variant"], r["_seed"] = "A", seed_a
            elif seed_b:
                r["_variant"], r["_seed"] = "B", seed_b
            else:
                continue
            rows.append(r)

    if not rows:
        raise SystemExit("No rows matched the given prefixes. Check --variant-a-prefix/--variant-b-prefix "
                          "against the 'policy' column in your CSV.")

    # per-seed mean, within each (variant, users, size)
    per_seed = defaultdict(list)
    for r in rows:
        per_seed[(r["_variant"], r["users"], r["size"], r["_seed"])].append(r[args.metric])
    seed_means = defaultdict(list)
    for (variant, users, size, seed), vals in per_seed.items():
        seed_means[(variant, users, size)].append(mean(vals))

    configs = sorted(set((u, s) for (_, u, s) in seed_means))
    out_rows = []
    print(f"Metric: {args.metric}  (mean and 95% CI computed ACROSS per-seed means, not pooled reps)\n")
    header = f"{'Users':>5} {'Size':>8}   {args.variant_a_label:>28} (n seeds)   {args.variant_b_label:>28} (n seeds)   Seed means overlap?"
    print(header)
    print("-" * len(header))
    for users, size in configs:
        a = seed_means.get(("A", users, size), [])
        b = seed_means.get(("B", users, size), [])
        a_mean, a_sd, a_n = mean(a), stdev(a), len(a)
        b_mean, b_sd, b_n = mean(b), stdev(b), len(b)
        a_ci = t_crit(a_n - 1) * a_sd / math.sqrt(a_n) if a_n > 1 else float("nan")
        b_ci = t_crit(b_n - 1) * b_sd / math.sqrt(b_n) if b_n > 1 else float("nan")
        overlap = "yes (not distinguishable)" if (a_n > 1 and b_n > 1 and
                   (a_mean - a_ci) <= (b_mean + b_ci) and (b_mean - b_ci) <= (a_mean + a_ci)) else "no"
        print(f"{users:>5} {size:>8}   {a_mean:>22.1f} +/-{a_ci:<5.1f}({a_n})   {b_mean:>22.1f} +/-{b_ci:<5.1f}({b_n})   {overlap}")
        out_rows.append({"users": users, "size": size,
                          "A_mean": a_mean, "A_ci95": a_ci, "A_n_seeds": a_n,
                          "B_mean": b_mean, "B_ci95": b_ci, "B_n_seeds": b_n,
                          "overlap": overlap})

    with open(args.out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out_rows[0].keys()))
        w.writeheader()
        w.writerows(out_rows)
    print(f"\nWrote {args.out}")
    print('"Seed means overlap?" = yes means the two variants\' confidence intervals (computed across '
          'their 5 independent training seeds) overlap, i.e. the data does not support treating them as '
          'different -- that config should be reported as inconclusive rather than as a real effect.')


if __name__ == "__main__":
    main()