"""Reference trajectories for the Kotlin circadian ports (spec §6.4, Phase 3b).

Runs a light series through Arcascope's `circadian` package (Hannay19 single population, or Forger99 for comparison)
and writes the states at full double precision, so a Kotlin test can assert the port matches per state variable.

Outputs, for --out NAME.csv:
- NAME-light.csv   hours,lux for every step (the input, exactly as integrated)
- NAME.csv         hours and the model's three states, every --every steps (Hannay19: R,Psi,n; Forger99: x,xc,n)
- NAME.dlmo.csv    the DLMO times the package reports

Details the port has to copy exactly (circadian 1.0.3, models.py):
- Fixed-step RK4 (`CircadianModel.step_rk4`), light held constant over each step.
- The step from time[i-1] to time[i] uses input[i], the light at the END of the step, not the start.
- Light is photopic lux. Hannay19: alpha = alpha_0 * I^p / (I^p + I0), p = 1.5, I0 = 9325.
  Forger99: alpha = alpha_0 * (I / I0)^p, p = 0.5, I0 = 9500.
- Default initial conditions are the state at midnight on a 16L:8D schedule:
  Hannay19 [0.82041911, 1.71383697, 0.52318122], Forger99 [-0.0843259, -1.09607546, 0.45584306].
- CBTmin markers: scipy find_peaks on -cos(Psi) (Hannay19) or -x (Forger99), distance ceil(13 h / dt), where dt is
  time[1] - time[0]. DLMO = CBTmin - 7 h.

Usage:
  python hannay19_golden.py --light week.csv --out week-hannay19.csv [--model Forger99] [--every 10]
  python hannay19_golden.py --synthetic office --days 7 --out office-hannay19.csv   # before real light exists
"""

import argparse
import csv
import math

import numpy as np
from circadian.models import Forger99, Hannay19

MODELS = {"Hannay19": (Hannay19, ["R", "Psi", "n"]), "Forger99": (Forger99, ["x", "xc", "n"])}


def synthetic_light(schedule: str, days: int, dt_hours: float) -> tuple[np.ndarray, np.ndarray]:
    hours = np.arange(0.0, days * 24.0, dt_hours)
    clock = np.mod(hours, 24.0)
    if schedule == "office":
        # 07:00 to 23:00 at 300 lux indoors with an hour of 5000 lux outdoors at noon, dark overnight.
        lux = np.where((clock >= 7.0) & (clock < 23.0), 300.0, 0.0)
        lux = np.where((clock >= 12.0) & (clock < 13.0), 5000.0, lux)
    elif schedule == "late":
        # A late sleeper who rarely goes out: asleep 04:00 to 12:00, 200 lux indoors until 18:00, a 30 lux room until
        # 02:00, then a phone in a dark room at 5 lux. Most of the day sits in the sub-100 lux range Hannay19 is for.
        lux = np.where((clock >= 12.0) & (clock < 18.0), 200.0, 0.0)
        lux = np.where((clock >= 18.0) | (clock < 2.0), 30.0, lux)
        lux = np.where((clock >= 2.0) & (clock < 4.0), 5.0, lux)
    else:
        raise SystemExit(f"unknown schedule {schedule}")
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
    source.add_argument("--synthetic", choices=["office", "late"], help="generate this schedule instead")
    parser.add_argument("--days", type=int, default=7, help="days for --synthetic (default 7)")
    parser.add_argument("--dt", type=float, default=0.1, help="step in hours for --synthetic (default 0.1)")
    parser.add_argument("--model", choices=sorted(MODELS), default="Hannay19")
    parser.add_argument("--every", type=int, default=1, help="write every Nth state row (default 1)")
    parser.add_argument("--out", required=True, help="output CSV")
    args = parser.parse_args()

    hours, lux = read_light(args.light) if args.light else synthetic_light(args.synthetic, args.days, args.dt)
    model_class, state_names = MODELS[args.model]
    model = model_class()
    trajectory = model.integrate(hours, input=lux)
    base = args.out.removesuffix(".csv")

    with open(base + "-light.csv", "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["hours", "lux"])
        for h, light in zip(hours, lux):
            writer.writerow([repr(float(h)), repr(float(light))])

    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["hours", *state_names])
        for i in range(0, len(hours), args.every):
            writer.writerow([repr(float(hours[i])), *(repr(float(v)) for v in trajectory.states[i])])

    try:
        dlmos = model.dlmos(trajectory)
    except ValueError as error:  # the package refuses marker spacing it considers implausible
        dlmos = []
        print(f"no DLMO markers: {error}")
    with open(base + ".dlmo.csv", "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["dlmo_hours"])
        for d in dlmos:
            writer.writerow([repr(float(d))])

    final = trajectory.states[-1]
    print(f"{args.model} {len(hours)} points, final {state_names[0]}={final[0]:.6f} {state_names[1]}={math.fmod(final[1], 2 * math.pi):.6f} "
          f"n={final[2]:.6f}, {len(dlmos)} DLMOs")


if __name__ == "__main__":
    main()
