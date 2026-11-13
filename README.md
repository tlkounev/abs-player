# ABS Player

Vlastní klienti pro [Audiobookshelf](https://www.audiobookshelf.org/) se stejným ovládáním
a společnou synchronizací pozice.

| Složka | Aplikace | Verze |
|---|---|---|
| [`android/`](android/README.md) | telefon, Android Auto, Google TV (jedno APK) | 1.5.9 |
| [`win/`](win/README.md) | Windows 11, minipřehrávač v oznamovací oblasti | 1.2.11 |

## Co mají společné

- **Ovládání:** pruh kapitoly + tenký pruh celé knihy (oba posuvné), zbývající čas podle rychlosti,
  hlasitost (klik/klepnutí = ztlumit), rychlost (klik = další krok 0,75–2×, jemně po 0,05),
  seznam kapitol s hledáním bez diakritiky i podle čísla.
- **Synchronizace:** server ABS uloží vždy poslední poslanou pozici bez porovnání času. Obě aplikace
  proto pozici ukládají hned při pauze, před pokračováním si přečtou pozici ze serveru a převezmou
  ji, když ji posunulo jiné zařízení, a při pauze sledují změny z ostatních zařízení živě.
- **Přehrávat na:** přehrávání jde přesunout mezi telefonem, Google TV a PC (telefon → TV/PC,
  PC → TV/PC). Pozice se při přepnutí uloží a cíl pokračuje přesně odtud. Bez Chromecastu:
  přijímač (TV aplikace / Windows aplikace) si zvuk bere sám ze serveru.
  Protokol: HTTP + JSON v lokální síti, mDNS `_absplayer._tcp`, port 8765 – viz
  [`android/app/src/main/java/cz/ondra/absplayer/remote/Protocol.kt`](android/app/src/main/java/cz/ondra/absplayer/remote/Protocol.kt)
  a [`win/src/remote.js`](win/src/remote.js).

## Sestavení

Každý push do `main` sestaví na GitHubu (Actions) jen tu aplikaci, jejíž složka se změnila,
a vystaví ji v *Releases*: `android-v…` (APK), `win-v…` (instalátor + zip).
