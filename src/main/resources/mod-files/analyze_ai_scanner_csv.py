"""
analyze_ai_scanner_csv.py
Document Version 1.0.0
Creation date: 2026/07/14
Creator: Thalassicus

Analyzes CSV output from ThalAIScanner.java. Fully reusable across runs:
every column is discovered dynamically by its "AIModule:"/"NEED:" prefix,
so nothing here needs to change if the scanner's column set changes
(e.g. new NEEDs registered by another mod, new AIModules from a game update).

Usage:
    pip install pandas --break-system-packages
    python3 analyze_ai_scanner_csv.py ThalAIScanner.csv

Confirmed game-time constants (from Sett.txt / TIME.java), used only for the
calendar-time conversion below:
    SECONDS_PER_HOUR = 48
    HOURS_PER_DAY    = 24
    -> SECONDS_PER_DAY = 1152

One caveat on the calendar conversion specifically: TIME's own constructor
applies a half-day offset to currentSecond at game start
(`this.currentSecond += this.days.bitSeconds() * 0.5;`), so the raw
game_seconds value's phase alignment against the in-game clock UI (i.e.
which exact value corresponds to 00:00) has not been empirically confirmed.
The hour-of-day column below is offset by that half day to compensate, but
treat it as a best-effort estimate until spot-checked against a couple of
rows' actual displayed in-game time.
"""

import sys
import pandas as pd

SECONDS_PER_HOUR = 48
HOURS_PER_DAY = 24
SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY
HALF_DAY_OFFSET = SECONDS_PER_DAY * 0.5


def load(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path)

    module_cols = [c for c in df.columns if c.startswith("AIModule:")]
    need_cols = [c for c in df.columns if c.startswith("NEED:")]
    need_keys = sorted({c.split(":", 1)[1].rsplit("_", 1)[0] for c in need_cols})

    # Derived: total population per row, and each module's share of it.
    # Share is more comparable across rows than a raw count, since total
    # population can drift (births, deaths, migration) over a long run.
    df["_total_population"] = df[module_cols].sum(axis=1)
    for col in module_cols:
        df[col.replace("AIModule:", "share:")] = df[col] / df["_total_population"]

    # Derived: used and utilization per NEED. "used" = total - available,
    # matching the same available()/total() semantics RoomServiceInstance
    # already tracks; utilization is 0 for any NEED with zero built capacity
    # rather than a division-by-zero NaN.
    for key in need_keys:
        total_col = f"NEED:{key}_total"
        avail_col = f"NEED:{key}_available"
        df[f"used:{key}"] = df[total_col] - df[avail_col]
        df[f"utilization:{key}"] = (df[f"used:{key}"] / df[total_col]).where(df[total_col] > 0, 0.0)

    # Derived: calendar hour-of-day and day number, adjusted for TIME's
    # confirmed half-day startup offset. See the module docstring caveat.
    adjusted = df["game_seconds"] - HALF_DAY_OFFSET
    df["calendar_day"] = (adjusted // SECONDS_PER_DAY).astype(int) + 1
    df["calendar_hour"] = ((adjusted % SECONDS_PER_DAY) // SECONDS_PER_HOUR).astype(int)

    df.attrs["module_cols"] = module_cols
    df.attrs["need_keys"] = need_keys
    return df


def summarize(df: pd.DataFrame) -> None:
    module_cols = df.attrs["module_cols"]
    need_keys = df.attrs["need_keys"]

    print(f"Rows: {len(df)}")
    print(f"Time span: {df['game_seconds'].min():.1f} - {df['game_seconds'].max():.1f} game-seconds "
          f"({(df['game_seconds'].max() - df['game_seconds'].min()) / SECONDS_PER_DAY:.2f} calendar days)")
    print()

    print("Population (sanity check - should be roughly stable unless something notable happened):")
    print(f"  min={df['_total_population'].min():.0f}  "
          f"max={df['_total_population'].max():.0f}  "
          f"mean={df['_total_population'].mean():.1f}")
    print()

    print("AI module share of population (mean / min / max):")
    for col in sorted(module_cols, key=lambda c: -df[c].mean()):
        share_col = col.replace("AIModule:", "share:")
        name = col.replace("AIModule:", "")
        print(f"  {name:<15} mean={df[share_col].mean():>7.2%}  "
              f"min={df[share_col].min():>7.2%}  max={df[share_col].max():>7.2%}")
    print()

    print("Room-service utilization by NEED (mean / min / max), sorted highest first:")
    util_cols = [f"utilization:{k}" for k in need_keys]
    for key in sorted(need_keys, key=lambda k: -df[f"utilization:{k}"].mean()):
        col = f"utilization:{key}"
        print(f"  {key:<15} mean={df[col].mean():>7.2%}  "
              f"min={df[col].min():>7.2%}  max={df[col].max():>7.2%}")
    print()

    # Cross-check: instantaneous "Service" module headcount vs. summed
    # room-side occupancy across every NEED. These are NOT expected to match
    # exactly - AIModule:Service includes subjects walking to/from a room,
    # while room-side "used" only counts subjects currently occupying a
    # reserved slot - but Service should generally be >= summed room usage.
    # A large or frequent violation of that would be worth investigating.
    if "AIModule:Service" in df.columns:
        summed_used = df[[f"used:{k}" for k in need_keys]].sum(axis=1)
        violations = (summed_used > df["AIModule:Service"]).sum()
        print("Cross-check: AIModule:Service count vs. summed room-side usage across all NEEDs")
        print(f"  Rows where summed room usage EXCEEDED Service headcount: {violations} / {len(df)}")
        print("  (some violations are expected/benign - see comment in script - but many would be worth digging into)")
        print()

    print("Calendar-hour coverage (row count per hour-of-day - confirms the sampling actually")
    print("cuts across the full day rather than aliasing on one phase, e.g. always during work hours):")
    print(df["calendar_hour"].value_counts().sort_index().to_string())


def main() -> None:
    if len(sys.argv) != 2:
        print("Usage: python3 analyze_ai_scanner_csv.py <path-to-ThalAIScanner.csv>")
        sys.exit(1)

    df = load(sys.argv[1])
    summarize(df)


if __name__ == "__main__":
    main()
