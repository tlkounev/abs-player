#!/usr/bin/env python3
"""Generates the texts of both apps from i18n/strings.py:
  android/app/src/main/java/cz/ondra/absplayer/I18nData.kt  (SHARED + ANDROID)
  win/src/i18n.js, the block between @generated-begin / @generated-end  (SHARED + WINDOWS, Windows key names)
Checks that every text has all 15 languages and the same {placeholders} in each.
  python3 i18n/gen.py           write the files
  python3 i18n/gen.py --check   only check that they are up to date (CI)
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from strings import ANDROID, LANGS, SHARED, WIN_ALIASES, WIN_KEYS, WINDOWS  # noqa: E402

CHECK = "--check" in sys.argv
n = len(LANGS)
errors = []
for name, d in (("SHARED", SHARED), ("ANDROID", ANDROID), ("WINDOWS", WINDOWS)):
    for k, v in d.items():
        if len(v) != n:
            errors.append(f"{name}.{k}: {len(v)} texts, expected {n}")
            continue
        ph = sorted(set(re.findall(r"\{\w+\}", v[2])))
        for i, x in enumerate(v):
            if sorted(set(re.findall(r"\{\w+\}", x))) != ph:
                errors.append(f"{name}.{k} [{LANGS[i][0]}]: placeholders differ from English: {x}")
for k in set(SHARED) & (set(ANDROID) | set(WINDOWS)):
    errors.append(f"{k}: both in SHARED and in an app's own texts")
for wk, sk in WIN_ALIASES.items():
    if sk not in SHARED:
        errors.append(f"WIN_ALIASES.{wk} → {sk}: no such shared text")
win = {}
for k in WIN_KEYS:
    t = WINDOWS.get(k) or SHARED.get(WIN_ALIASES.get(k, k))
    if t is None:
        errors.append(f"Windows key {k}: no text")
    win[k] = t
for k in WINDOWS:
    if k not in WIN_KEYS:
        errors.append(f"WINDOWS.{k}: missing in WIN_KEYS")
if errors:
    sys.exit("i18n:\n  " + "\n  ".join(errors))


def kt(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


# ---------------------------------------------------------------- Android
android = {**SHARED, **ANDROID}
keys = list(android)
SEP = "\\u0001"
out = [
    "// Generated from i18n/strings.py (python3 i18n/gen.py) – do not edit by hand.",
    "package cz.ondra.absplayer",
    "",
    "internal object I18nData {",
    "    val CODES = listOf(" + ", ".join(kt(c) for c, _ in LANGS) + ")",
    "    val NAMES = listOf(" + ", ".join(kt(nm) for _, nm in LANGS) + ")",
    "    const val KEYS = " + kt("\u0001".join(keys)).replace("\u0001", SEP),
    "    val DATA = arrayOf(",
]
for i, (code, _) in enumerate(LANGS):
    out.append("        // " + code)
    out.append("        " + kt("\u0001".join(android[k][i] for k in keys)).replace("\u0001", SEP) + ",")
out += ["    )", "}", ""]
kt_path = os.path.join(ROOT, "android", "app", "src", "main", "java", "cz", "ondra", "absplayer", "I18nData.kt")
kt_text = "\n".join(out)

# ---------------------------------------------------------------- Windows
js_path = os.path.join(ROOT, "win", "src", "i18n.js")
js_old = open(js_path, encoding="utf-8").read()
m = re.search(r"(  // @generated-begin\n).*?(  // @generated-end)", js_old, re.S)
if not m:
    sys.exit("win/src/i18n.js: @generated-begin / @generated-end markers not found")
q = lambda s: json.dumps(s, ensure_ascii=False)  # noqa: E731
block = ["  const KEYS = ["]
for i in range(0, len(WIN_KEYS), 8):
    block.append("    " + ", ".join(q(k) for k in WIN_KEYS[i:i + 8]) + ",")
block.append("  ];")
block.append("  const T = {")
for i, (code, _) in enumerate(LANGS):
    block.append(f"    {code}: [")
    for k in WIN_KEYS:
        block.append("      " + q(win[k][i]) + ",")
    block.append("    ],")
block.append("  };")
js_text = js_old[: m.start()] + m.group(1) + "\n".join(block) + "\n" + m.group(2) + js_old[m.end():]

stale = []
for path, text in ((kt_path, kt_text), (js_path, js_text)):
    cur = open(path, encoding="utf-8").read() if os.path.exists(path) else None
    if cur != text:
        stale.append(os.path.relpath(path, ROOT))
        if not CHECK:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
if CHECK and stale:
    sys.exit("out of date (run python3 i18n/gen.py): " + ", ".join(stale))
print(f"ok: Android {len(keys)} texts, Windows {len(WIN_KEYS)} texts, {len(SHARED)} shared, × {n} languages"
      + ("" if CHECK else (" – written: " + ", ".join(stale) if stale else " – up to date")))
