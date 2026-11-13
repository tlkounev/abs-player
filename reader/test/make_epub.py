# Vytvoří zkušební EPUB 3: 6 kapitol různé délky (pro test čtečky a přiřazení kapitol).
import sys, zipfile, random
out = sys.argv[1]
random.seed(1)
words = "loď kapitánka stála u průzoru a dívala se na šedý povrch který se pomalu otáčel pod nimi navigátor mlčel".split()
lens = [3, 8, 5, 12, 4, 9]  # odstavců po 120 slovech
chapters = []
for i, n in enumerate(lens, 1):
    paras = []
    for p in range(n):
        txt = " ".join(random.choice(words) for _ in range(120))
        paras.append(f"<p>[k{i} o{p}] {txt}.</p>")
    chapters.append((f"Kapitola {i}", "\n".join(paras)))
z = zipfile.ZipFile(out, "w")
z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
items = "".join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, len(chapters)+1))
spine = "".join(f'<itemref idref="c{i}"/>' for i in range(1, len(chapters)+1))
z.writestr("OEBPS/content.opf", f'<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test</dc:identifier><dc:title>Test</dc:title><dc:language>cs</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>{items}</manifest><spine>{spine}</spine></package>')
nav = "".join(f'<li><a href="c{i}.xhtml">{t}</a></li>' for i, (t, _) in enumerate(chapters, 1))
z.writestr("OEBPS/nav.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head><body><nav epub:type="toc"><ol>{nav}</ol></nav></body></html>')
for i, (t, body) in enumerate(chapters, 1):
    z.writestr(f"OEBPS/c{i}.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{t}</title></head><body><h1>{t}</h1>{body}</body></html>')
z.close()
