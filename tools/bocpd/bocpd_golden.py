"""Golden files for core-model's Bocpd (analytics spec Tier 2), from bayesian_changepoint_detection 0.2.dev1.

    uv venv && uv pip install -r tools/bocpd/requirements.txt
    python tools/bocpd/bocpd_golden.py --out core-model/src/test/resources/bocpd

Each file holds the prior, the hazard, the values and, after each value, the run-length posterior R[0..t, t] at full
precision. The series are synthetic and seeded, so re-running gives identical files.
"""

from __future__ import annotations

import argparse
from functools import partial
from pathlib import Path

import numpy as np
from bayesian_changepoint_detection.online_changepoint_detection import StudentT, constant_hazard, online_changepoint_detection

SERIES = {
    # Onset in minutes after noon: 40 nights around 00:20, then 30 around 02:00.
    "onset-step": dict(seed=7, parts=[(40, 740.0, 40.0), (30, 840.0, 40.0)], prior=(740.0, 1.0, 1.0, 60.0 ** 2 / 2), lam=90.0),
    # A 7-night SRI with no change at all.
    "sri-flat": dict(seed=11, parts=[(80, 62.0, 6.0)], prior=(62.0, 1.0, 1.0, 8.0 ** 2 / 2), lam=90.0),
    # The package's own example prior, tiny and sharp, on a series with an outlier: numerically hard for the port.
    "sharp-prior": dict(seed=3, parts=[(25, 0.0, 1.0), (1, 12.0, 0.0), (25, 3.0, 1.0)], prior=(0.0, 1.0, 0.1, 0.01), lam=250.0),
}


def values_for(spec: dict) -> np.ndarray:
    rng = np.random.RandomState(spec["seed"])
    return np.concatenate([rng.normal(mean, sd, n) if sd > 0 else np.full(n, mean) for n, mean, sd in spec["parts"]])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, spec in SERIES.items():
        values = values_for(spec)
        mu, kappa, alpha, beta = spec["prior"]
        R, _ = online_changepoint_detection(values, partial(constant_hazard, spec["lam"]), StudentT(alpha, beta, kappa, mu))
        if not np.all(np.isfinite(R)):
            raise SystemExit(f"{name}: the reference produced non-finite probabilities")
        lines = [
            f"# bayesian_changepoint_detection 0.2.dev1, series {name}",
            f"prior {mu!r} {kappa!r} {alpha!r} {beta!r}",
            f"hazard {1 / spec['lam']!r}",
            "values " + " ".join(repr(float(v)) for v in values),
        ]
        for t in range(len(values) + 1):
            lines.append(f"R{t} " + " ".join(repr(float(p)) for p in R[0:t + 1, t]))
        (out / f"{name}.txt").write_text("\n".join(lines) + "\n")
        print(f"{name}: {len(values)} values")


if __name__ == "__main__":
    main()
