#!/usr/bin/env python3
"""Stitch chunked/snapshotted KSafe calibration logs into per-session exposure.

Why this exists
---------------
A single ride's calibration log does NOT arrive as one file. The on-device
logger uploads it in size-capped chunks (Binder transaction limit) and re-sends
periodically during the ride for crash resilience. Older builds reused one
filename for every chunk, so Telegram Desktop saved them as `name (2).csv`,
`(3)`… in ARRIVAL order (not logical order), and some downloads even concatenate
several sessions into one file. Newer builds name each chunk
`…_{session}_c000_…`, `_c001_`, … so the inbox stays ordered — but the analysis
still has to fold the pieces back together to count a ride once.

`analyze_calibration_logs.py` counts each *file* as a "ride", which inflates
duration/exposure when a session spans many chunk files. This helper instead
groups by the in-CSV `session=` field (tracking the active session across every
`LOG_START`, so a file that concatenates two sessions is split correctly) and
deduplicates rows by their absolute `timestamp_ms`. That dedup is robust for
BOTH layouts: overlapping snapshots (old) and disjoint chunks (new) collapse to
the same unique row set, so exposure and event counts are each counted once.

Usage
-----
    python3 scripts/stitch_calibration_logs.py [PATHS ...] [--root DIR]

PATHS may be individual CSVs or directories (scanned recursively). With no
PATHS, scans --root (default: ./logs). Point it at the Telegram download folder
to fold a fresh sweep, e.g.:

    python3 scripts/stitch_calibration_logs.py ~/Downloads/Telegram\\ Desktop
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = REPO_ROOT / "logs"

_KV = lambda data, key: (m.group(1) if (m := re.search(rf"{key}=([^,\"]+)", data)) else None)
# filename fallback session token: the 6-hex id immediately before the trailing
# _<device> label (…_{install}_{session}_<device>.csv). The device label is derived
# from Build.MODEL and varies (k24, Karoo-3, …), so match any non-"_" device token to
# end-of-name instead of hard-coding "k2".
_FNAME_SESS = re.compile(r"_([0-9a-fA-F]{6})_(?:c\d+_)?[^_]+\.csv$")


def iter_csvs(paths: list[Path], root: Path) -> list[Path]:
    targets = paths or [root]
    out: list[Path] = []
    for t in targets:
        if t.is_dir():
            out += sorted(t.rglob("*.csv"))
        elif t.suffix == ".csv":
            out.append(t)
    return out


def stitch(files: list[Path]):
    # session -> {timestamp_ms: raw_line}  (dedupes overlapping snapshots/chunks)
    rows: dict[str, dict[int, str]] = defaultdict(dict)
    meta: dict[str, dict[str, set]] = defaultdict(
        lambda: {"install": set(), "profile": set(), "preset": set(), "ver": set(), "dev": set()}
    )
    for f in files:
        cur = None
        m = _FNAME_SESS.search(f.name)
        fname_sess = m.group(1) if m else None
        try:
            text = f.read_text(errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            parts = line.split(",", 3)
            if len(parts) < 3 or not parts[0].isdigit():
                continue  # header / malformed
            ts, event = int(parts[0]), parts[2]
            data = parts[3] if len(parts) > 3 else ""
            if event == "LOG_START":
                cur = _KV(data, "session") or fname_sess
            sess = cur or fname_sess
            if sess is None:
                continue
            rows[sess][ts] = line
            for key, fld in (("install", "install_id"), ("profile", "profile"),
                             ("preset", "preset"), ("ver", "app_version"), ("dev", "device")):
                if (v := _KV(data, fld)):
                    meta[sess][key].add(v)
    return rows, meta


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Stitch chunked KSafe calibration logs into per-session exposure.")
    ap.add_argument("paths", nargs="*", type=Path, help="CSV files or directories (default: scan --root).")
    ap.add_argument("--root", type=Path, default=DEFAULT_ROOT, help=f"Root scanned when no PATHS given (default: {DEFAULT_ROOT}).")
    args = ap.parse_args(argv)

    files = iter_csvs(args.paths, args.root)
    if not files:
        print("No CSV files found.", file=sys.stderr)
        return 1
    rows, meta = stitch(files)

    one = lambda s: sorted(s)[0] if s else "?"
    recs = []
    for sess, tsmap in rows.items():
        ts_sorted = sorted(tsmap)
        dur_min = (ts_sorted[-1] - ts_sorted[0]) / 60000.0 if len(ts_sorted) > 1 else 0.0
        lines = list(tsmap.values())
        cnt = lambda tag: sum(1 for l in lines if f",{tag}," in l)
        recs.append({
            "sess": sess, "install": one(meta[sess]["install"]), "prof": one(meta[sess]["profile"]),
            "preset": one(meta[sess]["preset"]), "ver": one(meta[sess]["ver"]), "dev": one(meta[sess]["dev"]),
            "rows": len(lines), "min": dur_min,
            "impact_in": cnt("IMPACT_IN"), "impact_tmo": cnt("IMPACT_TMO"),
            "high_mag": cnt("HIGH_MAG"), "crash_ok": cnt("CRASH_OK"), "crash_no": cnt("CRASH_NO"),
        })
    recs.sort(key=lambda r: -r["min"])

    hdr = (f"{'session':8} {'install':7} {'prof':6} {'prst':6} {'ver':5} {'dev':4} "
           f"{'min':>6} {'rows':>5} {'IMP_IN':>6} {'IMP_TMO':>7} {'HIMAG':>6} {'CR_OK':>5} {'CR_NO':>5}")
    print(f"Stitched {len(files)} files into {len(recs)} sessions (dedup by session+timestamp_ms).\n")
    print(hdr); print("-" * len(hdr))
    tot = defaultdict(float)
    for r in recs:
        flag = "  ⚠FP" if r["crash_ok"] else ""
        print(f"{r['sess']:8} {r['install']:7} {r['prof']:6} {r['preset']:6} {r['ver']:5} {r['dev']:4} "
              f"{r['min']:6.1f} {r['rows']:5d} {r['impact_in']:6d} {r['impact_tmo']:7d} "
              f"{r['high_mag']:6d} {r['crash_ok']:5d} {r['crash_no']:5d}{flag}")
        for k in ("min", "impact_in", "impact_tmo", "high_mag", "crash_ok", "crash_no"):
            tot[k] += r[k]
    print("-" * len(hdr))
    print(f"sessions={len(recs)}  exposure={tot['min']/60:.1f} h ({tot['min']:.0f} min)  "
          f"IMPACT_IN={int(tot['impact_in'])}  IMPACT_TMO={int(tot['impact_tmo'])}  "
          f"HIGH_MAG={int(tot['high_mag'])}  CRASH_OK={int(tot['crash_ok'])}  CRASH_NO={int(tot['crash_no'])}")
    if tot["min"] > 0:
        print(f"FP-confirm rate = {tot['crash_ok']/(tot['min']/60):.2f} CRASH_OK/h   ·   "
              f"IMPACT_IN rate = {tot['impact_in']/(tot['min']/60):.1f}/h")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
