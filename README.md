# Resource-Aware Microservice Migration in Fog Computing

This repository contains the source code, experimental results, and supporting material for the research paper:

> **Resource-Aware Reinforcement Learning for Mobility-Driven Microservice Migration in Fog Computing**

## Overview

Fog computing places computation closer to mobile users to satisfy the latency requirements of modern IoT applications. As users move between fog nodes, microservices may need to migrate in order to maintain low latency and acceptable Quality of Service (QoS).

This project extends the iFogSim2 simulator with a resource-aware reinforcement learning framework for mobility-driven microservice migration. The proposed approach uses a Q-learning agent that incorporates a composite Resource Availability Score (ARS) into migration decisions.

The ARS combines:

- Available CPU resources
- Available memory resources
- Available bandwidth resources

The objective is to balance migration cost, latency, and resource availability while adapting dynamically to changes in user mobility and system conditions.

---

## Research Contributions

This work introduces:

- A Resource Availability Score (ARS) that combines CPU, RAM, and bandwidth availability.
- A Resource-Aware Q-Learning migration policy.
- A comparison against multiple baseline migration strategies.
- Multi-seed evaluation to assess training stability.
- Latency-energy trade-off analysis.
- Large-scale experimental evaluation using iFogSim2.

---

## Compared Policies

### 1. Always Migrate

Migrates all eligible microservices whenever a mobility handoff occurs.

### 2. Never Migrate

Keeps microservices at their initial placement.

### 3. Cost-Tuned Policy

Uses a weighted migration decision based on latency and migration cost.

### 4. MOFCO-Inspired Policy

Adapts the mobility-aware migration strategy proposed in the MOFCO framework.

### 5. Resource-Aware Q-Learning Policy (Proposed)

Uses reinforcement learning and the Resource Availability Score to guide migration decisions.

### 6. Q-Learning without ARS

Ablation version of the proposed method used to isolate the impact of resource-awareness.

---

## Repository Structure

```text
.
├── results/
│   ├── full_grid_summary.csv
│   ├── multiseed.csv
│   ├── multiseed_summary.csv
│   ├── results.csv
│   ├── results_summary.csv
│   ├── stability_check.csv
│   └── stability_check_summary.csv
│
├── src/
│   └── org/
│       └── fog/
│           ├── placement/
│           │   ├── MigrationPolicy.java
│           │   ├── MofcoInspiredPolicy.java
│           │   └── QLearningMigrationPolicy.java
│           │
│           └── test/
│               └── perfeval/
│                   └── MobilityExperiment.java
│
├── run_experiments.py
├── train_qlearning.py
├── seed_comparison.csv
├── seed_comparison_energy.csv
└── README.md
```

---

## Experimental Configuration

### User Counts

```text
1, 3, 6, 10
```

### Microservice Sizes

```text
10000
50000
100000
200000
400000
800000
```

### Training

```text
1000 Training Episodes
```

### Statistical Validation

```text
5 Independent Training Seeds
20 Repetitions Per Configuration
95% Confidence Intervals
```

---

## Requirements

### Software

- Java 21
- Python 3.x
- iFogSim2
- CloudSim

### Recommended IDEs

- IntelliJ IDEA
- Eclipse

---

## Running Experiments

### Train the Q-Learning Agent

```bash
python train_qlearning.py
```

### Run the Evaluation

```bash
python run_experiments.py
```

### Run Individual Experiment

```bash
java org.fog.test.perfeval.MobilityExperiment
```

---

## Results

The repository includes:

- Full experimental grid results
- Multi-seed validation results
- Energy measurements
- Migration statistics
- Stability analysis data

Results are stored inside:

```text
results/
```

---

## Reproducibility

This repository provides:

- Source code for all migration policies
- Experimental configurations
- Training scripts
- Raw result files
- Aggregated result summaries

allowing reproduction of all reported experiments.

---

## Original Simulator

This research extends the functionality provided by iFogSim2.

Users interested in the original simulator should refer to:

- iFogSim (2017)
- iFogSim2 (2022)

This repository contains the modifications, migration policies, experiments, and data developed for this research project.

---

## Citation

If you use this repository, please cite:

```bibtex
@article{keita2026,
  title={Resource-Aware Reinforcement Learning for Mobility-Driven Microservice Migration in Fog Computing},
  author={Keita, Mohamed Youssouf and Kumar, Ramesh},
  year={2026}
}
```

Additionally, please cite the original iFogSim and iFogSim2 publications.

---

## Authors

**Mohamed Youssouf Keita**  
SRM University-AP, India

**Dr. Ramesh Kumar**  
SRM University-AP, India

---

## Acknowledgements

This work builds upon the iFogSim2 simulator developed by Rajkumar Buyya and collaborators. Their contributions to edge and fog computing simulation research made this work possible.

---

## License

This project is provided for academic and research purposes.

Please respect the licensing requirements of iFogSim2 and all dependent software components.
