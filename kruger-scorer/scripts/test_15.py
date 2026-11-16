from pathlib import Path
from pistol_detector import analyze

root = Path(r"App Tiro v2\App Tiro v2\dianas\2026-11-15")
reals = [92, 89, 90, 92, 91, 94]
print("=== 15-11 ===")
ok = 0
for i, p in enumerate(sorted(root.glob("*.jpg"))):
    r = analyze(str(p))
    gt = reals[i] if i < len(reals) else None
    if "error" in r:
        print(p.name, r["error"])
        continue
    delta = None if gt is None else r["total"] - gt
    if delta == 0:
        ok += 1
    print(f"{p.name}: n={r['n']} total={r['total']} real={gt} delta={delta} {r['avisos']}")
print(f"Exact: {ok}/6")
