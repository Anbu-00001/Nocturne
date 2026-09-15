"""Reference trajectories for the Kotlin Hannay19 port (spec §6.4, Phase 3b).

Runs a light series through Arcascope's `circadian` package (Hannay19, single population) and writes every
state at full double precision, so a Kotlin test can assert the port matches within 1e-4 per state variable.

Input CSV: `hours,lux`, one row per time point on a fixed step (the package reads the step from the time
column). Output CSV: `hours,R,Psi,n`, plus `<output>.dlmo.csv` with the DLMO times the package reports.

Details the port has to copy exactly (circadian 1.0.3, models.py):
- Fixed-step RK4 (`CircadianModel.step_rk4`), light held constant over each step.
- The step from time[i-1] to time[i] uses input[i], the light at the END of the step, not the start.
- Light is photopic lux: alpha = alpha_0 * I^p / (I^p + I0), with p = 1.5 and I0 = 9325.
- Default initial condition [0.82041911, 1.71383697, 0.52318122] is the state at midnight on a 16L:8D schedule.
- DLMO = core body temperature minimum - 7 h, CBTmin where -cos(Psi) peaks (scipy find_peaks).

Usage:
  python hannay19_golden.py --light week.csv --out week-hannay19.csv
  python hannay19_golden.py --synthetic-days 7 --out synthetic-hannay19.csv   # pipeline check before real light exists
"""

import argparse
import csv
import math

import numpy as np
from circadian.models import Hannay19


def synthetic_light(days: int, dt_hours: float) -> tuple[np.ndarray, np.ndarray]:
    """07:00 to 23:00 at 300 lux indoors with an hour of 5000 lux outdoors at noon, dark overnight."""
    hours = np.arange(0.0, days * 24.0, dt_hours)
    clock = np.mod(hours, 24.0)
    lux = np.where((clock >= 7.0) & (clock < 23.0), 300.0, 0.0)
    lux = np.where((clock >= 12.0) & (clock < 13.0), 5000.0, lux)
    return hours, lux


def read_light(path: str) -> tuple[np.ndarray, np.ndarray]:
    with open(path, newline="") as f:
        rows = [(float(r["hours"]), float(r["lux"])) for r in csv.DictReader(f)]
    hours = np.array([h for h, _ in rows])
    steps = np.diff(hours)
    if len(steps) and not np.allclose(steps, steps[0]):
        raise SystemExit("light series must be on a fixed step")
    return hours, np.array([lux for _, lux in rows])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--light", help="CSV with hours,lux")
    source.add_argument("--synthetic-days", type=int, help="generate a schedule of this many days instead")
    parser.add_argument("--dt", type=float, default=0.1, help="step in hours for --synthetic-days (default 0.1)")
    parser.add_argument("--out", required=True, help="output CSV")
    args = parser.parse_args()

    hours, lux = read_light(args.light) if args.light else synthetic_light(args.synthetic_days, args.dt)
    model = Hannay19()
    trajectory = model.integrate(hours, input=lux)

    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["hours", "lux", "R", "Psi", "n"])
        for h, light, state in zip(hours, lux, trajectory.states):
            writer.writerow([repr(float(h)), repr(float(light)), *(repr(float(v)) for v in state)])

    try:
        dlmos = model.dlmos(trajectory)
    except ValueError as error:  # the package refuses marker spacing it considers implausible
        dlmos = []
        print(f"no DLMO markers: {error}")
    with open(args.out.removesuffix(".csv") + ".dlmo.csv", "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["dlmo_hours"])
        for d in dlmos:
            writer.writerow([repr(float(d))])

    final = trajectory.states[-1]
    print(f"{len(hours)} points, final R={final[0]:.6f} Psi={math.fmod(final[1], 2 * math.pi):.6f} n={final[2]:.6f}, {len(dlmos)} DLMOs")


if __name__ == "__main__":
    main()
