"""Turns build errors into GitHub annotations (readable without logging in).
Usage: python3 report.py <offset>   — prints 10 annotations starting at offset."""
import re
import sys

off = int(sys.argv[1]) if len(sys.argv) > 1 else 0
try:
    lines = open("build.log", encoding="utf-8", errors="replace").read().splitlines()
except FileNotFoundError:
    lines = []

picked = []
for i, ln in enumerate(lines):
    s = ln.strip()
    if s.startswith("e: ") or " error: " in s or s.startswith("ERROR:") or "AAPT: error" in s:
        picked.append(s)
    elif s.startswith("* What went wrong"):
        picked.append(" | ".join(x.strip() for x in lines[i + 1:i + 7] if x.strip()))

seen, uniq = set(), []
for p in picked:
    p = re.sub(r"file:///home/runner/work/[^/]+/[^/]+/app/src/main/java/com/taskpilot/app/", "", p)
    if p not in seen:
        seen.add(p)
        uniq.append(p)

if off == 0:
    print(f"::warning::{len(uniq)} distinct error lines found")
for p in uniq[off:off + 10]:
    p = p.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")[:900]
    print(f"::error::{p}")
