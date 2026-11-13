# ABS Player pro Windows

Webový klient Audiobookshelf jako samostatná aplikace v oznamovací oblasti Windows 11.

## Ovládání
- **Klik** na ikonu v liště → zobrazí / schová minipřehrávač.
- **Dvojklik** → celé okno Audiobookshelf.
- **Zavření celého okna** → schová se do lišty, přehrávání pokračuje.
- **Pravý klik** → menu: otevřít, přehrát/pozastavit, změnit server, jazyk, spouštění po přihlášení, ukončit.

### Minipřehrávač
- Zůstává otevřený, i když klikneš jinam. Schová se klikem na ikonu v liště, tlačítkem `_` nebo klávesou Esc.
- Dá se chytit kdekoli mimo tlačítka a přetáhnout; pozice se pamatuje (i po restartu).
- Dva pruhy průběhu: horní = aktuální kapitola, spodní (tenký) = celá kniha. Oba jdou posouvat.
  Kniha bez kapitol má jen jeden pruh.
- Hlasitost: klik = ztlumit / zrušit ztlumení, najetí myší nebo fokus = svislý posuvník, kolečko = ±5 %.
- Rychlost: klik = další krok (0.75–2×), kolečko = ±0.05.
- Klávesy: mezerník = play/pauza, ←/→ = skok, M = ztlumit, Enter = celé okno, Esc = schovat.
- Seznam kapitol: tlačítko se třemi tečkami pod „Otevřít celé okno“ nebo klik na „Kapitola x/y“.
  Okno naroste nahoru (u horního okraje obrazovky dolů), seznam sjede na aktuální kapitolu,
  dohrané mají háček, klik skočí na začátek kapitoly, pole Hledat filtruje podle názvu (bez ohledu
  na diakritiku) nebo čísla. Esc vymaže hledání / zavře seznam.
- Když nic nehraje, nabídne poslední rozposlouchané tituly. Při přehrávání se na tuto úvodní
  stránku vrátíš zaoblenou šipkou Zpět vpravo nahoře: právě hrající kniha je první a zvýrazněná (klik na ni
  nebo Esc = zpět do přehrávače), klik na jinou ji pustí od jejího místa. Aktivní je celý řádek knihy.

### Přehrávat na (TV, jiné PC) a z telefonu sem
- Tlačítko s monitorem v minipřehrávači (nebo menu ikony → *Přehrávat na*) otevře seznam zařízení.
- **Přepnutí:** když něco hraje, pozastaví se, pozice se uloží na server a přehrávání pokračuje
  na vybraném zařízení přesně odtud. Minipřehrávač pak ovládá to zařízení (pruhy, kapitoly,
  rychlost, hlasitost) a pod autorem ukazuje jeho jméno. Zpět se přepne výběrem tohoto PC.
- **Hledání přijímače:** když na zadaném portu nic neodpovídá, zkusí se porty 8765–8774 (TV bere
  první volný). Spárované zařízení, které přešlo na jiný port, se najde samo.
- Panel *Přehrávat na* je vysoký přesně podle obsahu; rozbalené párování okno protáhne.
  IP | port | kód jsou v jednom řádku s hodnotami uprostřed, pod nimi stav a Spárovat.
- Za párovacím kódem je adresa tohoto PC (IP:port) pro ruční párování.
- Přepnutí na TV pošle knihu hned; uložení pozice a zavření přehrávače tady doběhne na pozadí.
- **Přejmenování:** tužka u zařízení změní řádek na pole (Enter uloží, Esc zruší). Název tohoto
  PC se posílá i ostatním zařízením, název spárovaného zařízení platí jen tady.
- **Párování:** tlačítko „+ Spárovat zařízení (TV nebo PC)“ otevře formulář (křížek / Esc ho zavře).
  Zařízení v síti se najdou samy (mDNS), jinak zadej IP. Zadej šestimístný kód,
  který zařízení ukazuje. TV, která ještě není přihlášená k tvému účtu, chce navíc heslo k ABS
  (získá s ním vlastní přihlášení, heslo se neukládá).
- **PC jako přijímač:** telefon s ABS Player může přehrávání poslat sem. Párovací kód tohoto
  PC je dole v seznamu zařízení a v menu ikony. Port 8765 (při prvním spuštění Windows zeptá,
  zda povolit přístup v síti – povol pro soukromé sítě).

### Synchronizace pozice
Webový přehrávač ABS sám pozici při pozastavení neukládá a po pauze pokračuje ze své staré
pozice, i když mezitím posloucháš jinde (a přepíše ji). Aplikace to opravuje stejně jako ABS Player pro Android:
- při pozastavení hned uloží pozici na server,
- před pokračováním si přečte pozici ze serveru a převezme ji, pokud ji posunulo jiné zařízení,
- když je pozastavený, převezme změnu z jiného zařízení okamžitě (zobrazí „Pozice převzata…“).

### Jazyky
čeština, slovenština, angličtina, němčina, polština, francouzština, španělština, italština,
portugalština, nizozemština, maďarština, ukrajinština, ruština, čínština, japonština.
Výchozí podle jazyka Windows; mění se vlajkou v okně s adresou serveru nebo v menu ikony (Jazyk).
Týká se aplikace, jazyk samotného webu ABS se nastavuje v ABS.

### Aktualizace
- Menu ikony v liště (pravý klik) ukazuje nahoře verzi a *Zkontrolovat aktualizace*.
- Aplikace se při spuštění a pak každých 6 hodin podívá, jestli je novější verze; nic sama nestahuje.
- Když je, ukáže oznámení Windows *Je k dispozici verze …*: klik = stáhnout, tiše nainstalovat a
  aplikaci znovu spustit. Totéž nabízí menu ikony (*Stáhnout a nainstalovat verzi …*).
- *Zkontrolovat aktualizace* ukáže buď to oznámení, nebo *Máš nejnovější verzi*.

### Vypnutá TV
- Když je vybraná TV nedostupná, minipřehrávač nabídne *Přehrát na tomto zařízení* (pustí knihu
  tady od pozice uložené na serveru).
- Výběr vypnuté TV v *Přehrávat na*: aplikace pošle Wake-on-LAN (MAC adresu TV si zjistí z ARP,
  když je TV zapnutá; na TV musí být zapnuté probouzení přes síť / Wi‑Fi) a 30 s čeká
  (*Probouzím …*). Když TV naběhne, kniha se na ní spustí; když ne, hraje se dál tady.
- Zdroj: vydání `win-latest` na GitHubu (`latest.yml` + instalátor), ten po každém sestavení
  přepíše GitHub Actions. Funguje jen s veřejným repozitářem.

## Jak to funguje
Hlavní okno je Chromium (Electron) s webem ABS v perzistentní relaci (přihlášení zůstává).
Minipřehrávač volá přímo metody Vue komponent přehrávače webového klienta
(MediaPlayerContainer, PlayerUi, VolumeControl), takže se chová stejně jako ovládání ve webu,
včetně synchronizace postupu na server a uložení hlasitosti / rychlosti.

Nastavení: `%APPDATA%\ABS Player\config.json` (server, jazyk, pozice oken, spárovaná zařízení).
Při prvním spuštění se převezme nastavení i přihlášení z dřívější verze (Audiobookshelf Tray);
tu pak odinstaluj, jinak by běžely obě.

Ikona aplikace i v liště je originální logo Audiobookshelf (`assets/abs-logo.svg`
z repozitáře advplyr/audiobookshelf), ICO soubory generuje `npm run icons`.

## Sestavení
```
npm install
npm start          # spuštění pro vývoj
npm run dist       # ikony + vlajky + instalátor a zip do dist/
npm test           # automatické testy proti maketě přehrávače ABS (screenshoty do test/out/)
```

Instalátor pro Windows lze sestavit i na Linuxu (electron-builder potřebuje Wine).

## Čtení e-knih (EPUB)

Okno čtečky se stejnou stránkou `/reader` jako Android (epub.js): obsah vlevo, nahoře kapitola,
*Aa* a *Poslouchat od této strany*, dole průběh a strana. Listuje se šipkami, PageUp/PageDown
nebo kolečkem. Pozice se ukládá do ABS jako ve webové čtečce. Kde se čtení otevírá, se ještě
rozhoduje (návrh: 📖 Číst v seznamu Pokračovat v poslechu).
