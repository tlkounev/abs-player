# Screenshots

How the images in [`docs/screenshots`](../../docs/screenshots) are made. All books are in the public domain.

**Android** – run the *Screenshots* workflow (Actions → Screenshots → Run workflow). It:

1. builds a demo library: `make_library.py` (covers, quiet audio with real chapter names, `metadata.opf`, and
   `alice.epub` for the reader);
2. starts Audiobookshelf in Docker and fills it: `abs_seed.py` (user `demo` / `demo`, library, listening progress);
3. runs the debug APK on a phone and an Android TV emulator: `android.sh` steers the app through debug-only
   intent extras of `MainActivity` (`--es demo login|home|book|play|player|devices|reader|lang|back`);
4. pushes the raw PNGs to the branch `screenshots-raw` (also uploaded as workflow artifacts).

**Windows** – `win-shots.js` opens the real popup and reader window with mocked data:

```
cd win
xvfb-run -a npx electron --force-device-scale-factor=2 ../tools/screenshots/win-shots.js <demo-library> ../tools/screenshots/alice.epub <out-dir>
```

**README images** – `compose.py <raw-dir> docs/screenshots` scales the phone / TV shots and puts the Windows popups
on a backdrop.

`alice.epub`: *Alice's Adventures in Wonderland* by Lewis Carroll, illustrated by Gordon Robinson (1916),
from Project Gutenberg #19033 – public domain in the USA.
