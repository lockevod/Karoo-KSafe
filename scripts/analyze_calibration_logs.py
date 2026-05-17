#!/usr/bin/env python3
"""
KSafe calibration log aggregator.

Reads every CSV under logs/ (recursively), parses the PERIODIC / HIGH_MAG /
IMPACT_IN / IMPACT_TMO / CRASH_CONFIRMED / RST_SNAP / SPEEDDROP_EVAL events
and prints both a per-file mini-summary and a global aggregated summary
broken down by (device, profile, preset, app_version).

Robustness:
  - Handles both decimal-point ('27.7') and decimal-comma ('27,7') payloads
    so v1.2.0 logs from es/fr/de locales (pre-formatUs fix) parse cleanly.
  - Tolerates tiny logs (4-5 rows) — does not crash on missing sections.
  - Skips malformed lines silently and reports the count at the end.

Usage:
    python3 scripts/analyze_calibration_logs.py
    python3 scripts/analyze_calibration_logs.py path/to/specific.csv [more.csv ...]
    python3 scripts/analyze_calibration_logs.py --root logs/logs_sergi
"""

from __future__ import annotations

import argparse
import csv
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = REPO_ROOT / "logs"

# ─────────────────────────────────────────────────────────────────────────────
# v2.0.0 sensitivity preset → (smoothed_threshold, peak_threshold) in m/s².
# Mirrors `impactThresholds` and `peakImpactThresholds` in
# app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt
# (verbatim from lines 50-75). Used for the "retro-projection" pass that asks
# what v2.0.0 would have done with v1.2.0 logs.
V2_0_0_THRESHOLDS: dict[str, tuple[float, float]] = {
    "LOW":    (55.0, 60.0),
    "MEDIUM": (45.0, 50.0),
    "HIGH":   (35.0, 40.0),
}

# ─────────────────────────────────────────────────────────────────────────────
# Parsing

# kv pair regex: key=value where value is non-comma OR a decimal with a
# comma-as-separator like "23,7" (we'll fix locale on the value).
KV_PAIR = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)=([^,]*(?:,\d+(?!\d*=))*)")


def _normalize_decimal(value: str) -> str:
    """
    Convert European decimal-comma to dot, but only for simple numerics.
    'speed=23,7' → '23.7'; '7,7|6,8|59,8' → '7.7|6.8|59.8'.
    Leaves non-numeric values alone.
    """
    if "|" in value:
        return "|".join(_normalize_decimal(p) for p in value.split("|"))
    if re.fullmatch(r"-?\d+,\d+", value):
        return value.replace(",", ".")
    return value


def _parse_payload(payload: str) -> dict[str, str]:
    """Parse a 'k=v,k=v,...' KSafe payload tolerating decimal commas."""
    out: dict[str, str] = {}
    for m in KV_PAIR.finditer(payload):
        out[m.group(1)] = _normalize_decimal(m.group(2))
    return out


def _to_float(v: str | None) -> float | None:
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _row_iter(path: Path):
    """
    Yield (timestamp_ms, elapsed_s, event, payload_dict) for each data row.

    Handles the pre-fix v1.2.0 k24 quirk where the elapsed_s column was
    written as '23,3' (decimal comma) and thus split across two CSV cells.
    """
    with path.open(newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        try:
            header = next(reader)
        except StopIteration:
            return
        if not header or header[0].strip() != "timestamp_ms":
            return  # not a KSafe calibration log
        for row in reader:
            if not row:
                continue
            # Normal v2.x case: exactly 4 cells (last one is the quoted payload)
            if len(row) == 4:
                ts, el, ev, payload = row
            elif len(row) == 5:
                # v1.2.0 k24 quirk: elapsed_s split into "23","3"
                ts, el_int, el_frac, ev, payload = row
                el = f"{el_int}.{el_frac}"
            else:
                continue
            try:
                ts_ms = int(ts)
                el_s = float(el)
            except ValueError:
                continue
            yield ts_ms, el_s, ev, _parse_payload(payload)


# ─────────────────────────────────────────────────────────────────────────────
# Per-file summary

class FileSummary:
    def __init__(self, path: Path):
        self.path = path
        self.session = ""
        self.device = ""
        self.app_version = ""
        self.profile = ""
        self.preset = ""
        self.duration_s = 0.0
        self.event_counts: Counter[str] = Counter()
        self.high_mag_raw: list[float] = []
        self.high_mag_smooth: list[float] = []
        # Per-sample (raw, smooth) pairs for retro-projection. Same length as
        # high_mag_raw/smooth but kept as tuples so we can evaluate "would this
        # row have fired IMPACT_IN under v2.0.0 preset X" sample-by-sample.
        self.high_mag_pairs: list[tuple[float, float]] = []
        # IMPACT_IN raw/smooth at the firing sample (from CrashDetectionManager
        # logImpactEnter payload). Captured so we can replay them against v2.0.0
        # and see whether v2.0.0 would still trigger them.
        self.impact_in_pairs: list[tuple[float, float]] = []
        # IMPACT_TMO with reason=SPEED — the min_spd actually observed during
        # the impact window. Useful to spot "rider rode through it at >5 km/h"
        # vs "rider almost stopped but not quite".
        self.impact_tmo_speed_min_spd: list[float] = []
        self.periodic_speed: list[float] = []
        self.periodic_accel_dev: list[float] = []
        self.periodic_noise: list[float] = []
        self.grade_boost_active_count = 0
        self.periodic_count = 0
        self.impact_in_count = 0
        self.impact_tmo_count = 0
        self.impact_tmo_reasons: Counter[str] = Counter()
        self.crash_confirmed_count = 0
        # Each entry is the full payload dict of a CRASH_CONFIRMED row, plus
        # an `elapsed_min` field so post-hoc analysis can see when in the ride
        # it fired (real crash, drop test, false positive — all useful).
        self.crash_confirmed_payloads: list[dict[str, str | float]] = []
        self.rows_total = 0
        self.malformed = 0

    @property
    def is_tiny(self) -> bool:
        return self.rows_total <= 5

    @property
    def is_legacy(self) -> bool:
        """
        Pre-versioning logs (no `app_version` in LOG_START) or logs where the
        IMPACT_TMO schema didn't yet include `why_no_silence`. These typically
        come from the monolith era before the state-machine refactor; their
        IMPACT_TMO `?` reasons inflate the MEDIUM aggregate without telling us
        anything actionable about the current v2.0.0 code paths.
        """
        if not self.app_version:
            return True
        if not self.device:
            return True
        # Tolerate the case where the version is present but the reason column
        # is missing on every TMO — the log was written by an older build that
        # ran a hybrid schema.
        if self.impact_tmo_count > 0 and self.impact_tmo_reasons.get("?", 0) == self.impact_tmo_count:
            return True
        return False

    def absorb(self):
        for ts_ms, el_s, ev, p in _row_iter(self.path):
            self.rows_total += 1
            self.duration_s = max(self.duration_s, el_s)
            self.event_counts[ev] += 1

            if ev == "LOG_START":
                self.session = p.get("session", self.session)
                self.device = p.get("device", self.device)
                self.app_version = p.get("app_version", self.app_version)
            elif ev == "PERIODIC":
                self.periodic_count += 1
                self.profile = p.get("profile", self.profile)
                self.preset = p.get("preset", self.preset)
                self._push(self.periodic_speed, p.get("speed"))
                self._push(self.periodic_accel_dev, p.get("accel_dev"))
                self._push(self.periodic_noise, p.get("noise"))
                gb = _to_float(p.get("grade_boost"))
                if gb is not None and gb > 0:
                    self.grade_boost_active_count += 1
            elif ev == "HIGH_MAG":
                self._push(self.high_mag_raw, p.get("raw"))
                self._push(self.high_mag_smooth, p.get("smooth"))
                r = _to_float(p.get("raw"))
                s = _to_float(p.get("smooth"))
                if r is not None and s is not None:
                    self.high_mag_pairs.append((r, s))
            elif ev == "IMPACT_IN":
                self.impact_in_count += 1
                r = _to_float(p.get("raw"))
                s = _to_float(p.get("smooth"))
                if r is not None and s is not None:
                    self.impact_in_pairs.append((r, s))
            elif ev == "IMPACT_TMO":
                self.impact_tmo_count += 1
                reason = p.get("why_no_silence", "?")
                self.impact_tmo_reasons[reason] += 1
                if reason == "SPEED":
                    self._push(self.impact_tmo_speed_min_spd, p.get("min_spd"))
            elif ev == "CRASH_CONFIRMED":
                self.crash_confirmed_count += 1
                # Snapshot the full payload so a real crash (or drop-test) is
                # printed verbatim downstream. This is the canonical "true
                # positive" evidence — keep every field so post-hoc tuning
                # never lacks context. List, not set, in case the same session
                # somehow confirms twice.
                self.crash_confirmed_payloads.append({
                    "elapsed_min": el_s / 60.0,
                    **p,
                })

    @staticmethod
    def _push(target: list[float], v: str | None):
        f = _to_float(v)
        if f is not None:
            target.append(f)


# ─────────────────────────────────────────────────────────────────────────────
# Stats helpers

def _display_path(p: Path) -> Path | str:
    """Render a path nicely: relative to the repo when it lives inside it,
    absolute otherwise. Centralises the try/except so callers stay readable."""
    try:
        if p.is_absolute():
            return p.relative_to(REPO_ROOT)
    except ValueError:
        # Path is absolute but lives outside REPO_ROOT (e.g. user passed
        # --root pointing at ~/Downloads). Show the absolute path verbatim.
        return p
    return p


def _stats(xs: list[float]) -> dict[str, float]:
    if not xs:
        return {"n": 0, "min": 0.0, "max": 0.0, "mean": 0.0, "p50": 0.0, "p95": 0.0}
    xs_sorted = sorted(xs)
    n = len(xs_sorted)

    def pct(p: float) -> float:
        if n == 1:
            return xs_sorted[0]
        k = (n - 1) * p
        lo, hi = int(k), min(int(k) + 1, n - 1)
        return xs_sorted[lo] + (xs_sorted[hi] - xs_sorted[lo]) * (k - lo)

    return {
        "n": n,
        "min": xs_sorted[0],
        "max": xs_sorted[-1],
        "mean": statistics.fmean(xs_sorted),
        "p50": pct(0.50),
        "p95": pct(0.95),
    }


def _fmt_stats(xs: list[float]) -> str:
    if not xs:
        return "  (no samples)"
    s = _stats(xs)
    return f"  n={s['n']:<5d} min={s['min']:6.2f}  p50={s['p50']:6.2f}  mean={s['mean']:6.2f}  p95={s['p95']:6.2f}  max={s['max']:6.2f}"


# ─────────────────────────────────────────────────────────────────────────────
# Aggregation

def _bucket_key(fs: FileSummary) -> tuple[str, str, str, str]:
    return (
        fs.device or "?",
        fs.profile or "?",
        fs.preset or "?",
        fs.app_version or "?",
    )


# ─────────────────────────────────────────────────────────────────────────────
# v2.0.0 retro-projection
#
# For each (raw, smooth) sample observed under any version, evaluate the
# v2.0.0 impact-entry rule per preset:
#
#     would_fire = (peak > peak_threshold) OR (smooth > smoothed_threshold)
#
# `peak` is not in the v1.2.0 calibration payload (it's a SensorReader-only
# field, see SensorSample.peakMagnitude). We use `raw` as a LOWER BOUND for
# `peak` — peak is the max raw over the last peakWindowMs, so peak >= raw
# always. That means `raw > peak_threshold` is a SUFFICIENT-but-not-necessary
# condition for the peak branch firing; the count is conservative (it
# undercounts real fires via peak). The smoothed branch is exact.

def _project_pairs(pairs: list[tuple[float, float]]) -> dict[str, int]:
    """Count how many (raw, smooth) pairs would have crossed each v2.0.0 preset."""
    counts: dict[str, int] = {preset: 0 for preset in V2_0_0_THRESHOLDS}
    for raw, smooth in pairs:
        for preset, (smoothed_thr, peak_thr) in V2_0_0_THRESHOLDS.items():
            if smooth > smoothed_thr or raw > peak_thr:
                counts[preset] += 1
    return counts


def _headroom_line(label: str, p95_value: float, threshold: float) -> str:
    """One-line headroom report: how far p95 sits below the v2.0.0 threshold."""
    if p95_value <= 0:
        return f"  {label} p95={p95_value:6.2f}  → (no headroom calc, no samples)"
    delta = threshold - p95_value
    ratio = threshold / p95_value if p95_value > 0 else float("inf")
    return f"  {label} p95={p95_value:6.2f}  → v2.0.0 headroom: {delta:+5.1f} m/s² (×{ratio:.2f})"


def _print_per_file(summaries: list[FileSummary]):
    print("=" * 88)
    print(" Per-file summary")
    print("=" * 88)
    for fs in summaries:
        rel = _display_path(fs.path)
        tag = " (tiny)" if fs.is_tiny else ""
        dur_min = fs.duration_s / 60.0
        crash_flag = ""
        if fs.crash_confirmed_count > 0:
            crash_flag = f"  CRASH_CONFIRMED={fs.crash_confirmed_count}"
        print(f"\n· {rel}{tag}")
        print(f"  device={fs.device or '?'}  profile={fs.profile or '?'}  preset={fs.preset or '?'}  v{fs.app_version or '?'}")
        print(f"  duration≈{dur_min:5.1f} min  rows={fs.rows_total}  PERIODIC={fs.periodic_count}  HIGH_MAG={fs.event_counts.get('HIGH_MAG', 0)}  IMPACT_IN={fs.impact_in_count}  IMPACT_TMO={fs.impact_tmo_count}{crash_flag}")
        if fs.impact_tmo_reasons:
            reasons = ", ".join(f"{k}={v}" for k, v in fs.impact_tmo_reasons.most_common())
            print(f"  IMPACT_TMO reasons: {reasons}")


def _print_bucket(key: tuple[str, str, str, str], group: list[FileSummary]):
    device, profile, preset, version = key
    total_dur_min = sum(fs.duration_s for fs in group) / 60.0
    total_rows = sum(fs.rows_total for fs in group)
    total_periodic = sum(fs.periodic_count for fs in group)
    total_highmag = sum(fs.event_counts.get("HIGH_MAG", 0) for fs in group)
    total_impact_in = sum(fs.impact_in_count for fs in group)
    total_impact_tmo = sum(fs.impact_tmo_count for fs in group)
    total_crash = sum(fs.crash_confirmed_count for fs in group)
    grade_boost_pct = (
        100.0 * sum(fs.grade_boost_active_count for fs in group) / total_periodic
        if total_periodic
        else 0.0
    )
    hm_per_min = total_highmag / total_dur_min if total_dur_min > 0 else 0.0
    impact_in_per_hr = (total_impact_in / total_dur_min * 60.0) if total_dur_min > 0 else 0.0

    all_raw = [v for fs in group for v in fs.high_mag_raw]
    all_smooth = [v for fs in group for v in fs.high_mag_smooth]
    all_speed = [v for fs in group for v in fs.periodic_speed]
    all_accel_dev = [v for fs in group for v in fs.periodic_accel_dev]
    all_noise = [v for fs in group for v in fs.periodic_noise]
    all_high_mag_pairs = [pair for fs in group for pair in fs.high_mag_pairs]
    all_impact_in_pairs = [pair for fs in group for pair in fs.impact_in_pairs]
    all_speed_min_spd = [v for fs in group for v in fs.impact_tmo_speed_min_spd]

    tmo_reason_total: Counter[str] = Counter()
    for fs in group:
        tmo_reason_total.update(fs.impact_tmo_reasons)

    print(f"\n── device={device}  profile={profile}  preset={preset}  v{version}  (rides={len(group)}) ──")
    print(f"  total duration: {total_dur_min:6.1f} min   total rows: {total_rows}")
    print(f"  HIGH_MAG: total={total_highmag}  rate={hm_per_min:5.2f}/min")
    print(f"  IMPACT_IN: total={total_impact_in}  rate={impact_in_per_hr:5.2f}/h")
    if total_impact_in:
        tmo_pct = 100.0 * total_impact_tmo / total_impact_in
        print(f"  IMPACT_TMO: total={total_impact_tmo}  ({tmo_pct:.1f}% of IMPACT_IN)")
        if tmo_reason_total:
            reasons = ", ".join(f"{k}={v}" for k, v in tmo_reason_total.most_common())
            print(f"    reasons:        {reasons}")
    print(f"  CRASH_CONFIRMED: {total_crash}")
    print(f"  grade_boost active: {grade_boost_pct:5.1f}% of PERIODIC samples")
    print(f"  HIGH_MAG raw    (m/s²):")
    print(_fmt_stats(all_raw))
    print(f"  HIGH_MAG smooth (m/s²):")
    print(_fmt_stats(all_smooth))
    print(f"  PERIODIC speed (km/h):")
    print(_fmt_stats(all_speed))
    print(f"  PERIODIC accel_dev:")
    print(_fmt_stats(all_accel_dev))
    print(f"  PERIODIC noise:")
    print(_fmt_stats(all_noise))

    # ── v2.0.0 retro-projection on HIGH_MAG samples ─────────────────────────
    #
    # Asks: of all the HIGH_MAG samples this bucket observed (samples above
    # the 22 m/s² logging floor but below the *bucket-version* thresholds),
    # how many would have crossed each v2.0.0 preset's impact bar?
    #
    # Useful in two directions:
    #   • v1.2.0 buckets → "if these riders had run v2.0.0, how many fresh
    #     IMPACT_IN events would the new thresholds have produced?"
    #   • v2.0.0 buckets → "if we shipped MEDIUM instead of LOW, how many
    #     terrain spikes would have crossed the bar?"
    if all_high_mag_pairs:
        proj = _project_pairs(all_high_mag_pairs)
        total = len(all_high_mag_pairs)
        print(f"  v2.0.0 retro-projection — HIGH_MAG samples that would cross each preset:")
        for p in ("LOW", "MEDIUM", "HIGH"):
            c = proj[p]
            pct = 100.0 * c / total if total else 0.0
            smoothed_thr, peak_thr = V2_0_0_THRESHOLDS[p]
            print(f"    {p:<7s}: {c:4d} / {total:<4d}  ({pct:4.1f}%)   thr smooth>{smoothed_thr:.0f} or peak>{peak_thr:.0f}")

    # ── v2.0.0 retro-projection on IMPACT_IN events ─────────────────────────
    #
    # Asks: of the IMPACT_IN events this bucket actually fired (under its
    # own version's thresholds), how many would v2.0.0 still fire?
    # Conservative on the peak branch (raw is a lower bound for peak).
    if all_impact_in_pairs:
        proj = _project_pairs(all_impact_in_pairs)
        total = len(all_impact_in_pairs)
        print(f"  v2.0.0 retro-projection — IMPACT_IN events that v2.0.0 would still fire:")
        for p in ("LOW", "MEDIUM", "HIGH"):
            c = proj[p]
            pct = 100.0 * c / total if total else 0.0
            print(f"    {p:<7s}: {c:4d} / {total:<4d}  ({pct:4.1f}%)")

    # ── Headroom vs v2.0.0 thresholds ───────────────────────────────────────
    #
    # How far the p95 of observed HIGH_MAG values sits below each v2.0.0
    # threshold. A small or negative headroom signals a rider whose terrain
    # routinely brushes the impact bar — bandera roja for FP risk if they
    # switch to that preset.
    if all_raw or all_smooth:
        raw_p95 = _stats(all_raw)["p95"] if all_raw else 0.0
        smooth_p95 = _stats(all_smooth)["p95"] if all_smooth else 0.0
        print(f"  Headroom (HIGH_MAG p95 vs v2.0.0 thresholds):")
        for p in ("LOW", "MEDIUM", "HIGH"):
            smoothed_thr, peak_thr = V2_0_0_THRESHOLDS[p]
            print(f"    {p:<7s}:")
            print("      " + _headroom_line("raw   ", raw_p95, peak_thr).lstrip())
            print("      " + _headroom_line("smooth", smooth_p95, smoothed_thr).lstrip())

    # ── IMPACT_TMO why_no_silence=SPEED distribution ────────────────────────
    #
    # Shows the rider's actual minimum speed during the impact window when
    # the speed gate was the reason for not confirming. min_spd much higher
    # than crashConfirmSpeedKmh = "rider rode straight through it" → terrain.
    # min_spd close to crashConfirmSpeedKmh = "almost confirmed, near miss"
    # → worth investigating those rides individually.
    if all_speed_min_spd:
        s = _stats(all_speed_min_spd)
        print(f"  IMPACT_TMO reason=SPEED  min_spd during window (km/h):")
        print(f"  n={s['n']:<5d} min={s['min']:6.2f}  p50={s['p50']:6.2f}  mean={s['mean']:6.2f}  p95={s['p95']:6.2f}  max={s['max']:6.2f}")

    # ── CRASH_CONFIRMED detail (true positives — real crashes / drop tests) ─
    #
    # Always dump verbatim. These are the ground-truth events any future
    # tuning has to keep firing. If this section ever shows entries from a
    # ride the rider didn't actually crash on, that's a v2.0.0 false positive
    # that needs investigation.
    if total_crash:
        print(f"  CRASH_CONFIRMED events (verbatim, n={total_crash}):")
        for fs in group:
            for entry in fs.crash_confirmed_payloads:
                rel = _display_path(fs.path)
                elapsed = entry.get("elapsed_min", 0.0)
                kv = ", ".join(f"{k}={v}" for k, v in entry.items() if k != "elapsed_min")
                print(f"    • {rel} @ t≈{elapsed:.1f}min: {kv}")


def _print_aggregated(summaries: list[FileSummary], header: str):
    buckets: dict[tuple[str, str, str, str], list[FileSummary]] = defaultdict(list)
    for fs in summaries:
        buckets[_bucket_key(fs)].append(fs)

    print("\n" + "=" * 88)
    print(f" {header}")
    print("=" * 88)
    if not buckets:
        print(" (no logs in this cohort)")
        return
    for key in sorted(buckets):
        _print_bucket(key, buckets[key])


# ─────────────────────────────────────────────────────────────────────────────
# Main

def _discover(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(p for p in root.rglob("*.csv") if p.is_file())


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Aggregate KSafe calibration CSV logs.")
    ap.add_argument("paths", nargs="*", help="Specific CSV files to analyse. Defaults to all CSVs under --root.")
    ap.add_argument("--root", type=Path, default=DEFAULT_ROOT, help=f"Root directory to scan recursively (default: {DEFAULT_ROOT.relative_to(REPO_ROOT)}).")
    args = ap.parse_args(argv)

    paths = [Path(p) for p in args.paths] if args.paths else _discover(args.root)
    if not paths:
        print(f"No CSV files found (looked under {args.root}).", file=sys.stderr)
        return 1

    summaries: list[FileSummary] = []
    for p in paths:
        if not p.exists():
            print(f"⚠️  skip (missing): {p}", file=sys.stderr)
            continue
        fs = FileSummary(p)
        try:
            fs.absorb()
        except Exception as e:  # noqa: BLE001
            print(f"⚠️  failed to parse {p}: {e}", file=sys.stderr)
            continue
        if fs.rows_total == 0:
            print(f"⚠️  skip (empty/not a KSafe log): {p}", file=sys.stderr)
            continue
        summaries.append(fs)

    if not summaries:
        print("No usable logs after parsing.", file=sys.stderr)
        return 1

    _print_per_file(summaries)

    # Partition: legacy = no app_version, no device, or pre-state-machine schema
    # (every IMPACT_TMO `?`). Legacy logs contaminate aggregated stats because
    # they predate the state-machine refactor — their IMPACT_TMO entries lack
    # `why_no_silence` and their PERIODIC rows lack `noise`. Keep them as a
    # diagnostic-only cohort.
    primary = [fs for fs in summaries if not fs.is_legacy]
    legacy = [fs for fs in summaries if fs.is_legacy]

    _print_aggregated(primary, "Aggregated by (device, profile, preset, app_version)")
    if legacy:
        _print_aggregated(
            legacy,
            "Legacy cohort (no version/device or pre-state-machine schema — diagnostic only)",
        )

    print("\n" + "=" * 88)
    tiny = sum(1 for fs in summaries if fs.is_tiny)
    print(
        f" Parsed {len(summaries)} logs  ·  primary: {len(primary)}  ·  legacy: {len(legacy)}"
        f"  ·  tiny (≤5 rows): {tiny}"
    )
    print("=" * 88)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
