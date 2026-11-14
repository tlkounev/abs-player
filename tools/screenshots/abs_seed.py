#!/usr/bin/env python3
"""Sets up a fresh Audiobookshelf server (docker) with the demo library for the screenshots.

Creates the root user demo/demo, the library "Audiobooks" on /audiobooks, waits for the scan and
sets listening progress, so "Continue" and the progress bars have something to show.
Usage: abs_seed.py [http://localhost:13378]
"""
import json, sys, time, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:13378"
TOKEN = None


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + TOKEN} if TOKEN else {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        try:
            return json.loads(raw)
        except ValueError:
            return raw.decode(errors="replace")


for _ in range(90):
    try:
        st = call("GET", "/status")
        break
    except Exception:
        time.sleep(2)
else:
    sys.exit("server did not start")
print("status:", st)
if not st.get("isInit"):
    call("POST", "/init", {"newRoot": {"username": "demo", "password": "demo"}})
u = call("POST", "/login", {"username": "demo", "password": "demo"})["user"]
TOKEN = u.get("accessToken") or u["token"]

libs = call("GET", "/api/libraries")["libraries"]
lib = libs[0] if libs else call("POST", "/api/libraries", {
    "name": "Audiobooks", "folders": [{"fullPath": "/audiobooks"}], "icon": "audiobookshelf",
    "mediaType": "book", "provider": "google"})
call("POST", f"/api/libraries/{lib['id']}/scan")

items = []
for _ in range(60):
    items = call("GET", f"/api/libraries/{lib['id']}/items?limit=50")["results"]
    if len(items) >= 7 and all(i["media"].get("duration") for i in items):
        break
    time.sleep(3)
print("items:", len(items))
by = {i["media"]["metadata"]["title"]: i for i in items}
for t, i in by.items():
    m = i["media"]
    print(f"  {t}: {round(m.get('duration') or 0)} s, chapters {m.get('numChapters')}, ebook {m.get('ebookFormat')}")

# listening progress (fraction of the book); listened in this order, so "Continue" starts with Alice
PROGRESS = [("Pride and Prejudice", None), ("Dracula", 0.08), ("The Time Machine", 0.62),
            ("A Study in Scarlet", 0.31), ("Alice's Adventures in Wonderland", 0.37)]
for title, frac in PROGRESS:
    i = by.get(title)
    if not i:
        continue
    d = i["media"]["duration"]
    body = ({"isFinished": True, "progress": 1, "currentTime": d, "duration": d} if frac is None else
            {"progress": frac, "currentTime": round(d * frac, 1), "duration": d})
    call("PATCH", f"/api/me/progress/{i['id']}", body)
    time.sleep(1.1)  # distinct lastUpdate → stable order
print("seeded")
