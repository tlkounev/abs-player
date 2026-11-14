#!/usr/bin/env python3
"""Turns the raw screenshots into the README images in docs/screenshots.
Usage: compose.py <raw-dir> <out-dir>
Windows popups (transparent, 2x) are placed on a soft backdrop with a shadow; phone/TV shots are scaled down."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFilter

RAW, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)


def backdrop(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    a, b = (38, 52, 78), (92, 70, 104)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3)))
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse([w * 0.15, h * 0.1, w * 0.95, h * 1.3], fill=110)
    glow = glow.filter(ImageFilter.GaussianBlur(w // 6))
    img.paste(Image.new("RGB", (w, h), (227, 165, 77)), (0, 0), glow.point(lambda v: v // 3))
    return img


def place(canvas, shot, x, y):
    alpha = shot.split()[-1]
    sh = Image.new("L", canvas.size, 0)
    sh.paste(alpha, (x, y + 16))
    sh = sh.filter(ImageFilter.GaussianBlur(28)).point(lambda v: int(v * 0.55))
    canvas.paste(Image.new("RGB", canvas.size, (0, 0, 0)), (0, 0), sh)
    canvas.paste(shot, (x, y), alpha)


def popups(names, out, cols):
    """cols: list of columns, each a list of shot names stacked top to bottom."""
    shots = {n: Image.open(os.path.join(RAW, n)).convert("RGBA") for n in names}
    gap = 64
    col_h = [sum(shots[n].height for n in c) + gap * (len(c) - 1) for c in cols]
    W = sum(max(shots[n].width for n in c) for c in cols) + gap * (len(cols) + 1)
    H = max(col_h) + 2 * gap
    canvas = backdrop(W, H)
    x = gap
    for c, ch in zip(cols, col_h):
        y = gap + (max(col_h) - ch) // 2
        for n in c:
            place(canvas, shots[n], x, y)
            y += shots[n].height + gap
        x += max(shots[n].width for n in c) + gap
    canvas.save(os.path.join(OUT, out), optimize=True)
    print(out, canvas.size)


def scaled(src, out, width):
    img = Image.open(src).convert("RGB")
    h = round(img.height * width / img.width)
    img.resize((width, h), Image.LANCZOS).save(os.path.join(OUT, out), optimize=True)
    print(out, (width, h))


if os.path.exists(os.path.join(RAW, "win-1-player.png")):
    popups(["win-1-player.png", "win-5-continue.png", "win-3-play-on.png"], "windows-popup.png",
           [["win-1-player.png", "win-5-continue.png"], ["win-3-play-on.png"]])
    popups(["win-2-chapters.png"], "windows-chapters.png", [["win-2-chapters.png"]])
    scaled(os.path.join(RAW, "win-6-reader.png"), "windows-reader.png", 1600)
for f in sorted(glob.glob(os.path.join(RAW, "phone-*.png"))):
    scaled(f, os.path.basename(f), 540)
for f in sorted(glob.glob(os.path.join(RAW, "tv-*.png"))):
    scaled(f, os.path.basename(f), 1280)
