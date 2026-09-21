#!/usr/bin/env python3
"""Static checks on the notebooks, for things a kernel only reports at runtime.

Currently:
  - valid nbformat JSON (the notebooks are generated, so this catches a bad generator)
  - no top-level `val _ = ...`: Almond emits a pretty-printer call naming every
    top-level binding, backquoted. For a wildcard that is `` `_` ``, which Scala 3
    rejects outright, so the cell fails to compile before it runs.
"""
import json
import pathlib
import sys

failed = 0
notebooks = sorted(pathlib.Path("notebooks").glob("*.ipynb"))
if not notebooks:
    print("no notebooks found", file=sys.stderr)
    sys.exit(2)

for path in notebooks:
    problems = []
    try:
        nb = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        print(f"✗ {path}: invalid JSON — {e}")
        failed += 1
        continue

    if "nbformat" not in nb or "cells" not in nb:
        problems.append("missing nbformat/cells")

    for i, cell in enumerate(nb.get("cells", [])):
        if cell.get("cell_type") != "code":
            continue
        for line in cell.get("source", []):
            stripped = line.rstrip("\n")
            # Top level == column zero; an indented `val _` is a local and is fine.
            if stripped.startswith(("val _", "var _")) and not stripped.startswith(("val __", "var __")):
                problems.append(f"cell {i}: top-level wildcard binding — Almond cannot backquote `_`")

    if problems:
        failed += 1
        print(f"✗ {path}")
        for p in problems:
            print(f"    {p}")
    else:
        print(f"✓ {path} ({len(nb['cells'])} cells)")

print("\nnotebook checks passed" if not failed else f"\n{failed} notebook(s) failed")
sys.exit(0 if not failed else 1)
