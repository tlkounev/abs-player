#!/usr/bin/env python3
"""Generates app/src/main/java/cz/ondra/absplayer/I18n.kt from strings.py (checks that every key has all 15 languages)."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from strings import LANGS, S  # noqa: E402

n = len(LANGS)
bad = [k for k, v in S.items() if len(v) != n]
if bad:
    sys.exit("missing translations: " + ", ".join(f"{k} ({len(S[k])})" for k in bad))
for k, v in S.items():
    ph = sorted(set(re.findall(r"\{\w+\}", v[0])))
    for i, x in enumerate(v):
        if sorted(set(re.findall(r"\{\w+\}", x))) != ph:
            sys.exit(f"placeholder mismatch in {k} [{LANGS[i][0]}]: {x}")


def lit(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


SEP = "\\u0001"
keys = list(S)
out = [
    "// Generated from android/i18n/strings.py (python3 i18n/gen.py) – do not edit by hand.",
    "package cz.ondra.absplayer",
    "",
    "internal object I18nData {",
    "    val CODES = listOf(" + ", ".join(lit(c) for c, _ in LANGS) + ")",
    "    val NAMES = listOf(" + ", ".join(lit(nm) for _, nm in LANGS) + ")",
    "    const val KEYS = " + lit("\u0001".join(keys)).replace("\u0001", SEP),
    "    val DATA = arrayOf(",
]
for i, (code, _) in enumerate(LANGS):
    out.append("        // " + code)
    out.append("        " + lit("\u0001".join(S[k][i] for k in keys)).replace("\u0001", SEP) + ",")
out += ["    )", "}", ""]
dst = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "cz", "ondra", "absplayer", "I18nData.kt")
with open(dst, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print(f"ok: {len(keys)} keys × {n} languages → {os.path.normpath(dst)}")
