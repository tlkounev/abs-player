# ABS Player pro Android

Vlastní Android klient pro Audiobookshelf: telefon, Android Auto a Google TV v jedné aplikaci.
Verze 1.5.0.

## Co umí

**Telefon**
- Záložky Pokračovat (rozposlouchané ze všech zařízení) | Přidané | Knihovna | Série | Autoři:
  - *Přidané* = Nedávno přidané knihy a Nedávné série (stejné police jako na Homu Audiobookshelfu);
  - Knihovna, Série i Autoři mají hledání;
  - v mřížkách je pod názvem série s pořadím (např. „Duna #2“).
- Detail knihy a přehrávač: autor a série jsou odkazy. Autor otevře své knihy, série své knihy v pořadí.
- Přehrávač je rozložený jako minipřehrávač ABS Player pro Windows (varianta B):
  - nahoře velký obal;
  - pod ním název, autor · série a kapitola; vpravo časovač, *Přehrávat na* a seznam kapitol;
  - pruh kapitoly a tenký pruh celé knihy. Oba se dají posouvat a při tažení se popisky mění podle pozice.
    Časy jsou uplynulý a zbývající podle rychlosti;
  - ovládání: hlasitost | ⏮ | ↺10 | ▶ | ↻10 | ⏭ | rychlost.
- Hlasitost: klepnutí = ztlumit, podržení = svislý posuvník.
- Rychlost: klepnutí = další krok 0,75–2×, podržení = − / + po 0,05.
- Stažením dolů (kdekoli na obrazovce) nebo šipkou se přehrávač zavře na hlavní stránku.
- Když nic nehraje, přehrávač nabídne poslední rozposlouchané tituly.
- Detail knihy: kapitoly jsou sbalené, otevře je řádek *Kapitoly (n)*.
- Ovládání dálkovým ovladačem (TV): vybraná položka má oranžový rámeček, tlačítko Play bílý a je
  větší. Detail knihy začíná na *Pokračovat*, přehrávač na Play. V přehrávači ↑/↓ přechází mezi
  řádky a ←/→ na pruhu kapitoly posouvá o 10 s, na pruhu knihy o 1 minutu. Vybraný pruh nemá rámeček,
  jeho kolečko pulzuje. Šipka dolů ze záložek nebo z hledání jde na první viditelnou položku vlevo.
  V detailu knihy jde z pruhu přehrávání šipka nahoru vždy na *Pokračovat*. Na TV a na šířku je
  obal vlevo a ovládání vpravo.
- Seznam kapitol s hledáním bez diakritiky i podle čísla, časovač (minuty nebo konec kapitoly).
- Jazyk aplikace se mění vlajkou na hlavní stránce. Je to stejných 15 jazyků jako ve Windows:
  čeština, slovenština, angličtina, němčina, polština, francouzština, španělština, italština,
  portugalština, nizozemština, maďarština, ukrajinština, ruština, čínština a japonština.
  Výchozí je jazyk systému. Texty jsou v `i18n/strings.py`, `python3 i18n/gen.py` z nich generuje `I18nData.kt`.
- Minipřehrávač dole (↺10, ▶), ovládání z notifikace, sluchátek a Bluetooth.

**Android Auto**
- Záložky Pokračovat | Knihovna | Série | Nové, obaly v mřížce, průběh u každé knihy.
- Kniha se v autě tváří jako seznam kapitol: pruh ukazuje kapitolu (ne 12h knihu),
  ⏮/⏭ skáče po kapitolách, fronta = kapitoly.
- Tlačítka jako v oficiální aplikaci: ⏯ ↺10 ↻10 | rychlost ⏮ ⏭. Rychlost ukazuje aktuální hodnotu
  (třeba 1.25x), klepnutí = další krok 0,75–2×. ⏮ ⏭ skáčou po kapitolách. Hledání hlasem/textem.
- Fronta začíná kapitolou před právě přehrávanou (Android Auto ji vždy otevře nahoře).
- Obrazovku přehrávání (barvy podle obalu, vlnitý pruh, rozložení) kreslí samo Android Auto, aplikace
  dodává jen data, obal a tlačítka. Proto vypadá stejně jako u ostatních přehrávačů.
- Tlačítko „Pokračovat“ po připojení auta obnoví poslední knihu.

**Přehrávat na – TV i PC**
- Cílem může být Google TV (toto APK) nebo PC s ABS Player pro Windows 1.1.4+.
- Seznam zařízení vypadá jako ve Windows: toto zařízení první (oranžový proužek, „toto zařízení“),
  spárovaná s ×, každé jde přejmenovat tužkou. Nový název tohoto zařízení uvidí i ostatní
  (při dalším hledání / párování), název spárovaného zařízení platí jen tady.
- Párování: *Přehrávat na → Spárovat zařízení*, zařízení ukáže šestimístný kód.
  Heslo k ABS je potřeba jen pro TV, která ještě není přihlášená k tvému účtu.

**Google TV (stejné APK)**
- Na TV je stejná obrazovka jako na telefonu. Běží tam přijímač na pozadí (spouští se i po zapnutí TV);
  párovací kód TV a její adresa jsou v *Přehrávat na* dole.
- Když na TV pošleš přehrávání, aplikace se sama otevře s přehrávačem (i když byla jen schovaná
  pod Homem) – jen s povolením
  „Zobrazení přes jiné aplikace“ (tlačítko *Povolit* v *Přehrávat na*; když TV to nastavení nemá:
  `adb shell appops set cz.ondra.absplayer SYSTEM_ALERT_WINDOW allow`).
- Odchod z aplikace na TV (domů, jiná aplikace) přehrávání pozastaví a uloží pozici. Během
  přehrávání se nespouští spořič obrazovky.
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
Lokálně sestavené APK je podepsané ladicím klíčem. Každý push do `main`, který mění složku `android/`,
sestaví APK na GitHubu (Actions) a podepíše ho pevným klíčem z tajných proměnných repozitáře:

- `ANDROID_KEYSTORE_B64` – keystore (PKCS12, alias `absplayer`) v base64,
- `ANDROID_KEYSTORE_PASSWORD` – heslo ke keystoru i klíči.

Bez nich se APK podepíše ladicím klíčem a vydání (tag `android-v…`) se nevytvoří, protože takové APK
nejde nainstalovat přes předchozí verzi.

## Aktualizace

- Verze je v menu ⋮ na hlavní stránce, tamtéž *Zkontrolovat aktualizace* (i na TV).
- Aplikace se při spuštění (nejvýš jednou denně) podívá do vydání na GitHubu (`android-v…`).
  Je-li novější verze, ukáže nahoře pruh *K dispozici je verze …*; ✕ ho skryje do další verze.
- *Aktualizovat* stáhne APK a předá ho instalátoru Androidu, ten se zeptá na potvrzení.
  Poprvé Android chce povolit „instalaci neznámých aplikací“ pro ABS Player.
- Funguje jen s veřejným repozitářem (stahuje se bez přihlášení) a s APK podepsanými stejným klíčem.

## Instalace

- Telefon: nainstalovat APK.
- Android Auto: aplikace instalovaná mimo Obchod Play se v autě zobrazí až po zapnutí neznámých
  zdrojů. V telefonu: Nastavení → vyhledat „Android Auto“ → sjet dolů a 10× klepnout na *Verze* →
  potvrdit vývojářský režim → menu ⋮ → *Nastavení pro vývojáře* → *Neznámé zdroje*.
- Google TV: `adb connect <ip-tv>` a `adb install app-release.apk` (nebo přes správce souborů).
  Aplikace se objeví v řádku aplikací.

## Omezení

- Jen audioknihy (podcasty ne), bez stahování offline (streamuje s velkým bufferem).
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
- `tv/` – přijímač na TV (služba, HTTP API, start po zapnutí TV).
