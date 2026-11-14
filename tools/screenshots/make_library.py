#!/usr/bin/env python3
"""Builds a small demo audiobook library for the README screenshots (public-domain titles).

Every book: a folder with a cover, an .m4b with real chapter names (quiet audio, so the files are tiny)
and a metadata.opf (author, narrator, series, description). "Alice" also gets the EPUB for the reader.
Usage: make_library.py <out-dir> [alice.epub]
"""
import os, shutil, subprocess, sys, textwrap
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
EPUB = sys.argv[2] if len(sys.argv) > 2 else None
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
FONT2 = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

BOOKS = [
    dict(author="Lewis Carroll", title="Alice's Adventures in Wonderland", narrator="Elizabeth Klett", year="1865",
         colors=("#2b5876", "#4e4376"), epub=True,
         desc="Alice follows a White Rabbit down a rabbit hole into a world of nonsense and wonder.",
         chapters=["Down the Rabbit-Hole", "The Pool of Tears", "A Caucus-Race and a Long Tale",
                   "The Rabbit Sends in a Little Bill", "Advice from a Caterpillar", "Pig and Pepper",
                   "A Mad Tea-Party", "The Queen's Croquet-Ground", "The Mock Turtle's Story",
                   "The Lobster Quadrille", "Who Stole the Tarts?", "Alice's Evidence"]),
    dict(author="Arthur Conan Doyle", title="A Study in Scarlet", narrator="David Clarke", year="1887",
         series=("Sherlock Holmes", "1"), colors=("#7a2e2a", "#2a0f0d"),
         desc="Dr John Watson meets Sherlock Holmes and the two take on their first case together.",
         chapters=["Mr. Sherlock Holmes", "The Science of Deduction", "The Lauriston Garden Mystery",
                   "What John Rance Had to Tell", "Our Advertisement Brings a Visitor", "Tobias Gregson Shows What He Can Do",
                   "Light in the Darkness"]),
    dict(author="Arthur Conan Doyle", title="The Sign of the Four", narrator="David Clarke", year="1890",
         series=("Sherlock Holmes", "2"), colors=("#5c3d2e", "#1d120c"),
         desc="A young woman receives a pearl every year from an unknown sender – and then a letter.",
         chapters=["The Science of Deduction", "The Statement of the Case", "In Quest of a Solution",
                   "The Story of the Bald-Headed Man", "The Tragedy of Pondicherry Lodge", "Sherlock Holmes Gives a Demonstration"]),
    dict(author="H. G. Wells", title="The Time Machine", narrator="Mark Nelson", year="1895",
         colors=("#134e5e", "#0b2b33"),
         desc="A Victorian inventor travels to the year 802,701 and finds humanity split in two.",
         chapters=["The Time Traveller", "The Machine", "The Golden Age", "The Morlocks", "The Further Vision", "Epilogue"]),
    dict(author="Jane Austen", title="Pride and Prejudice", narrator="Karen Savage", year="1813",
         colors=("#6b5233", "#2e2215"),
         desc="Elizabeth Bennet and Mr Darcy – first impressions, second thoughts.",
         chapters=["Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5", "Chapter 6", "Chapter 7", "Chapter 8"]),
    dict(author="Bram Stoker", title="Dracula", narrator="Simon Vance", year="1897",
         colors=("#3a1c32", "#120910"),
         desc="Jonathan Harker travels to Transylvania to help a count buy a house in England.",
         chapters=["Jonathan Harker's Journal", "Jonathan Harker's Journal (continued)", "Letter from Miss Mina Murray",
                   "Dr. Seward's Diary", "Lucy Westenra's Diary", "The Voyage of the Demeter"]),
    dict(author="Jules Verne", title="Twenty Thousand Leagues Under the Seas", narrator="Fred Green", year="1870",
         colors=("#0f3443", "#34e89e"),
         desc="Professor Aronnax joins Captain Nemo aboard the submarine Nautilus.",
         chapters=["A Runaway Reef", "The Pros and Cons", "As Master Wishes", "Ned Land", "At Random", "Under Full Steam"]),
]


def cover(path, b):
    w, h = 800, 800
    top, bot = (tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) for c in b["colors"])
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))
    # frame and ornament
    d.rectangle([40, 40, w - 40, h - 40], outline=(227, 165, 77), width=3)
    d.line([(w // 2 - 60, 520), (w // 2 + 60, 520)], fill=(227, 165, 77), width=3)
    f = ImageFont.truetype(FONT, 64)
    lines = textwrap.wrap(b["title"], 16)
    y = 360 - len(lines) * 40
    for line in lines:
        tw = d.textlength(line, font=f)
        d.text(((w - tw) / 2, y), line, font=f, fill=(245, 236, 216))
        y += 80
    f2 = ImageFont.truetype(FONT2, 36)
    tw = d.textlength(b["author"].upper(), font=f2)
    d.text(((w - tw) / 2, 560), b["author"].upper(), font=f2, fill=(227, 165, 77))
    img.save(path, quality=90)


def audio(path, chapters, minutes=22):
    meta = [";FFMETADATA1"]
    t = 0
    for i, c in enumerate(chapters):
        n = (minutes + (i * 7) % 15) * 60 * 1000
        meta += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={t}", f"END={t + n}", f"title={c}"]
        t += n
    mf = path + ".meta"
    open(mf, "w").write("\n".join(meta) + "\n")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-t", str(t / 1000),
                    "-i", "anoisesrc=color=brown:amplitude=0.002:sample_rate=8000",
                    "-i", mf, "-map_metadata", "1", "-map_chapters", "1",
                    "-ac", "1", "-c:a", "aac", "-b:a", "8k", path], check=True)
    os.remove(mf)


def opf(path, b):
    s = b.get("series")
    series = (f'<meta name="calibre:series" content="{s[0]}"/><meta name="calibre:series_index" content="{s[1]}"/>' if s else "")
    open(path, "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
<dc:title>{b['title']}</dc:title><dc:creator opf:role="aut">{b['author']}</dc:creator><dc:creator opf:role="nrt">{b['narrator']}</dc:creator>
<dc:date>{b['year']}</dc:date><dc:language>en</dc:language><dc:description>{b['desc']}</dc:description>{series}
</metadata></package>""")


for b in BOOKS:
    parts = [OUT, b["author"]] + ([b["series"][0]] if b.get("series") else []) + [b["title"]]
    d = os.path.join(*parts)
    os.makedirs(d, exist_ok=True)
    cover(os.path.join(d, "cover.jpg"), b)
    audio(os.path.join(d, b["title"] + ".m4b"), b["chapters"])
    opf(os.path.join(d, "metadata.opf"), b)
    if b.get("epub") and EPUB:
        shutil.copy(EPUB, os.path.join(d, b["title"] + ".epub"))
    print("book:", d)
