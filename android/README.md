# ABS Player pro Android

Vlastní Android klient pro Audiobookshelf: telefon, Android Auto a Google TV v jedné aplikaci.
Verze 1.1.0.

## Co umí

**Telefon**
- Pokračovat (rozposlouchané ze všech zařízení), Knihovna s hledáním, Série, detail knihy s kapitolami.
- Přehrávač: pruh aktuální kapitoly + tenký pruh celé knihy, ±30 s, předchozí/další kapitola,
  rychlost 0,5–3× (krok 0,05), časovač (minuty nebo konec kapitoly), seznam kapitol.
- Ovládání jako v ABS Player pro Windows: pruh kapitoly + tenký pruh celé knihy (oba se dají posouvat,
  při tažení se popisky mění podle pozice), uplynulý a zbývající čas podle rychlosti,
  hlasitost (klepnutí = ztlumit, podržení = posuvník), rychlost (klepnutí = další krok 0,75–2×,
  podržení = jemně po 0,05), seznam kapitol s hledáním bez diakritiky i podle čísla.
  Když nic nehraje, přehrávač nabídne poslední rozposlouchané tituly.
- Minipřehrávač dole, ovládání z notifikace, sluchátek a Bluetooth.

**Android Auto**
- Záložky Pokračovat | Knihovna | Série | Nové, obaly v mřížce, průběh u každé knihy.
- Kniha se v autě tváří jako seznam kapitol: pruh ukazuje kapitolu (ne 12h knihu),
  ⏮/⏭ skáče po kapitolách, fronta = kapitoly.
- Vlastní tlačítka: −30 s, +30 s, rychlost. Hledání hlasem/textem.
- Tlačítko „Pokračovat“ po připojení auta obnoví poslední knihu.

**Přehrávat na – TV i PC**
- Cílem může být Google TV (toto APK) nebo PC s ABS Player pro Windows 1.1.4+.
- Párování: *Přehrávat na → Spárovat zařízení*, zařízení ukáže šestimístný kód.
  Heslo k ABS je potřeba jen pro TV, která ještě není přihlášená k tvému účtu.

**Google TV (stejné APK)**
- Na TV běží přijímač na pozadí (spouští se i po zapnutí TV) a ukazuje párovací kód.
- Telefon: *Přehrávat na → Spárovat zařízení* → vybrat TV, zadat kód (a heslo, pokud TV není přihlášená).
- Pak *Přehrávat na → TV*: telefon pozastaví, uloží pozici na server a TV pokračuje přesně odtud.
  Telefon se stane ovladačem (stav, kapitoly, rychlost, časovač). Zpět na telefon funguje stejně.
- TV si přehrává sama ze serveru (vlastní přihlášení, vlastní synchronizace), bez Chromecastu.

## Synchronizace pozice

Server ABS uloží vždy poslední poslanou pozici, bez porovnání času. Aplikace proto:

1. Před každým spuštěním / pokračováním si přečte pozici ze serveru. Když se liší od toho,
   co naposledy zapsala, posunul ji jiný klient → převezme ji („Pozice převzata z: …“).
2. Když je pozastavená, sleduje živé události serveru (socket) a pozici přebírá okamžitě.
3. Při přehrávání posílá pozici každých 10 s a hned při pauze, posunu, zastavení a konci knihy.
4. Když se sync nepovede (bez signálu), drží pozici lokálně a zkouší znovu; při dalším otevření
   knihy vyhraje, pokud je novější než serverová.
5. Výpadek streamu (tunel) zkusí znovu navázat 5×; dopředu se bufferuje až 15 minut.

Pozor: stará pozice z jiné aplikace (oficiální app, web) se pořád může zapsat, pokud ta aplikace
pokračuje ze staré pozice. Tahle aplikace ale sama nikdy nepřepíše novější pozici starou.

## Sestavení

Potřeba: Android Studio (Ladybug nebo novější) nebo JDK 17+ a Android SDK 35.

```
gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk` (podepsané ladicím klíčem, pro sideload).
Každý push do `main`, který mění složku `android/`, sestaví APK na GitHubu (Actions) a vystaví ho v Releases (tag `android-v…`).

## Instalace

- Telefon: nainstalovat APK.
- Android Auto: aplikace instalovaná mimo Obchod Play se v autě zobrazí až po zapnutí neznámých
  zdrojů. V telefonu: Nastavení → vyhledat „Android Auto“ → sjet dolů a 10× klepnout na *Verze* →
  potvrdit vývojářský režim → menu ⋮ → *Nastavení pro vývojáře* → *Neznámé zdroje*.
- Google TV: `adb connect <ip-tv>` a `adb install app-release.apk` (nebo přes správce souborů).
  Aplikace se objeví v řádku aplikací.

## Omezení verze 1.0

- Jen audioknihy (podcasty ne), jen čeština, bez stahování offline (streamuje s velkým bufferem).
- Ovládání TV/PC funguje v lokální síti (mDNS + HTTP port 8765); přes 5G zvenku zatím ne.
- Telefon sám přijímačem není (Android nedovolí spustit přehrávání aplikaci na pozadí); po otevření
  a stisku play ale vždy převezme nejnovější pozici z PC/TV.
  Pokud je TV v jiné VLAN, zadej při párování IP ručně.

## Struktura

- `data/` – API klient ABS (JWT access/refresh tokeny, starší servery s trvalým tokenem), socket.io.
- `player/ChapterPlayer.kt` – fasáda přehrávače: kniha jako playlist kapitol.
- `player/SyncManager.kt` – pravidla synchronizace výše.
- `player/PlayerEngine.kt` – ExoPlayer + sync + časovač, sdílené telefonem i TV.
- `player/PlaybackService.kt`, `BrowseTree.kt` – notifikace a Android Auto.
- `remote/` – cíle přehrávání (telefon / TV), párování, vyhledávání TV v síti.
- `tv/` – přijímač na TV (služba, HTTP API, UI ovladatelné dálkovým ovladačem).
