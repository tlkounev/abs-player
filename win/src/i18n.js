// Překlady a vlajky. Použitelné v hlavním procesu (require) i v oknech (<script src="i18n.js">).
(function (root) {
  // ---------- vlajky (SVG, poměr 3:2) ----------
  function star(cx, cy, r, rot) {
    const pts = [];
    for (let i = 0; i < 10; i++) {
      const a = (rot || 0) - Math.PI / 2 + (i * Math.PI) / 5;
      const rr = i % 2 ? r * 0.382 : r;
      pts.push((cx + rr * Math.cos(a)).toFixed(2) + ',' + (cy + rr * Math.sin(a)).toFixed(2));
    }
    return '<polygon fill="#ffde00" points="' + pts.join(' ') + '"/>';
  }
  const h3 = (a, b, c) => `<rect width="30" height="20" fill="${c}"/><rect width="30" height="13.34" fill="${b}"/><rect width="30" height="6.67" fill="${a}"/>`;
  const v3 = (a, b, c) => `<rect width="30" height="20" fill="${c}"/><rect width="20" height="20" fill="${b}"/><rect width="10" height="20" fill="${a}"/>`;
  const svg = (body, vb) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb || '0 0 30 20'}" preserveAspectRatio="none">${body}</svg>`;

  const FLAGS = {
    cs: svg('<rect width="30" height="10" fill="#fff"/><rect y="10" width="30" height="10" fill="#d7141a"/><path d="M0 0 15 10 0 20z" fill="#11457e"/>'),
    sk: svg(h3('#fff', '#0b4ea2', '#ee1c25') +
      '<path d="M5.2 4.4h8.2v6.3c0 3.1-2.4 4.6-4.1 5.3-1.7-.7-4.1-2.2-4.1-5.3z" fill="#ee1c25" stroke="#fff" stroke-width=".7"/>' +
      '<path d="M8.8 5.6h1v7.4h-1z M7.3 7.1h4v.9h-4z M7.6 9.1h3.4v.9H7.6z" fill="#fff"/>' +
      '<path d="M5.9 13.2c.8-1 1.6-1.1 2.3-.6.5-.9 1.6-.9 2.1 0 .7-.5 1.5-.4 2.3.6-.9 1.3-2.2 2.1-3.3 2.6-1.1-.5-2.5-1.3-3.4-2.6z" fill="#0b4ea2"/>'),
    en: svg('<clipPath id="ukA"><path d="M0 0v30h60V0z"/></clipPath>' +
      '<clipPath id="ukB"><path d="M30 15h30v15z M30 15v15H0z M30 15H0V0z M30 15V0h30z"/></clipPath>' +
      '<g clip-path="url(#ukA)"><path d="M0 0v30h60V0z" fill="#012169"/>' +
      '<path d="M0 0l60 30M60 0 0 30" stroke="#fff" stroke-width="6"/>' +
      '<path d="M0 0l60 30M60 0 0 30" clip-path="url(#ukB)" stroke="#c8102e" stroke-width="4"/>' +
      '<path d="M30 0v30M0 15h60" stroke="#fff" stroke-width="10"/><path d="M30 0v30M0 15h60" stroke="#c8102e" stroke-width="6"/></g>', '0 0 60 30'),
    de: svg(h3('#000', '#dd0000', '#ffce00')),
    pl: svg('<rect width="30" height="20" fill="#dc143c"/><rect width="30" height="10" fill="#fff"/>'),
    fr: svg(v3('#0055a4', '#fff', '#ef4135')),
    es: svg('<rect width="30" height="20" fill="#aa151b"/><rect y="5" width="30" height="10" fill="#f1bf00"/>'),
    it: svg(v3('#009246', '#fff', '#ce2b37')),
    pt: svg('<rect width="30" height="20" fill="#ff0000"/><rect width="12" height="20" fill="#006600"/>' +
      '<circle cx="12" cy="10" r="4" fill="none" stroke="#ffcc00" stroke-width="1.2"/>' +
      '<path d="M10.3 7.8h3.4v3.1c0 1.2-.8 1.9-1.7 2.3-.9-.4-1.7-1.1-1.7-2.3z" fill="#ff0000" stroke="#fff" stroke-width=".5"/>'),
    nl: svg(h3('#ae1c28', '#fff', '#21468b')),
    hu: svg(h3('#ce2939', '#fff', '#477050')),
    uk: svg('<rect width="30" height="20" fill="#ffd500"/><rect width="30" height="10" fill="#005bbb"/>'),
    ru: svg(h3('#fff', '#0039a6', '#d52b1e')),
    zh: svg('<rect width="30" height="20" fill="#ee1c25"/>' + star(5, 5, 3) + star(10, 2, 1, 0.4) + star(12, 4, 1, 0.9) + star(12, 7, 1, 0) + star(10, 9, 1, 0.4)),
    ja: svg('<rect width="30" height="20" fill="#fff"/><circle cx="15" cy="10" r="6" fill="#bc002d"/>')
  };

  // ---------- jazyky (pořadí v menu) ----------
  const LANGS = [
    ['cs', 'Čeština'], ['sk', 'Slovenčina'], ['en', 'English'], ['de', 'Deutsch'], ['pl', 'Polski'],
    ['fr', 'Français'], ['es', 'Español'], ['it', 'Italiano'], ['pt', 'Português'], ['nl', 'Nederlands'],
    ['hu', 'Magyar'], ['uk', 'Українська'], ['ru', 'Русский'], ['zh', '中文'], ['ja', '日本語']
  ].map(([code, name]) => ({ code, name, flag: FLAGS[code] }));

  // ---------- texty ----------
  const KEYS = [
    'menuOpen', 'menuPlayPause', 'menuChangeServer', 'menuLanguage', 'menuAutostart', 'menuQuit',
    'openFull', 'hide', 'prevCh', 'nextCh', 'back', 'fwd', 'rate', 'sleep', 'volume', 'chapter', 'book',
    'continue', 'play', 'unavailable', 'loadingPlayer', 'login', 'nothing', 'openAbs',
    'setupTitle', 'setupExample', 'setupSave', 'setupInvalid', 'language',
    'offTitle', 'offRetry', 'offAuto',
    'chapters', 'search', 'closeList',
    'playOn', 'pairNew', 'pairCodeThis', 'pairHint', 'ipAddr', 'port', 'code', 'password', 'pairBtn', 'needPass', 'badPassword', 'noResponse', 'offline', 'ready', 'playingT', 'pausedT', 'unpair', 'positionTaken', 'playingOn', 'deviceOffline', 'searching', 'thisDevice', 'pairNewBtn', 'updCheck', 'updChecking', 'updDownloading', 'updRestart', 'updReady', 'updReadyBody', 'updNone', 'updError', 'nowPlaying', 'leftT', 'backToPlayer', 'updAvailable', 'updClickInstall', 'updInstall', 'updDialogBody', 'updInstallBtn', 'updLater', 'goBack', 'playHere', 'waking', 'wakeFailed'
  ];
  const T = {
    cs: ['Otevřít Audiobookshelf', 'Přehrát / pozastavit', 'Změnit server…', 'Jazyk', 'Spouštět po přihlášení do Windows', 'Ukončit',
      'Otevřít celé okno', 'Schovat', 'Předchozí kapitola', 'Další kapitola', 'Zpět o {s} s', 'Vpřed o {s} s', 'Rychlost (klik = další, kolečko = jemně)', 'Časovač spánku', 'Hlasitost (klik = ztlumit)', 'Kapitola {n}/{total}', 'Kniha',
      'Pokračovat v poslechu', 'Přehrát', 'Server nedostupný nebo se stránka ještě načítá.', 'Načítám přehrávač…', 'Přihlas se v hlavním okně.', 'Nic se nepřehrává.', 'Otevřít Audiobookshelf',
      'Adresa serveru Audiobookshelf', 'Např. {a} nebo {b}', 'Uložit a otevřít', 'Neplatná adresa', 'Jazyk',
      'Server Audiobookshelf není dostupný', 'Zkusit znovu', 'Další pokus automaticky za {n} s',
      'Kapitoly', 'Hledat', 'Zavřít seznam',
      'Přehrávat na', 'Spárovat zařízení', 'Párovací kód tohoto PC: {code}', 'TV: otevři ABS Player, ukáže kód. Telefon: v ABS Player zadej kód tohoto PC.', 'IP adresa', 'Port', 'Kód ze zařízení', 'Heslo k ABS', 'Spárovat', 'Zařízení není přihlášené k tvému účtu – zadej heslo k ABS.', 'Špatné heslo.', 'Zařízení neodpovídá.', 'Nedostupné', 'Připraveno', 'Hraje: {t}', 'Pozastaveno: {t}', 'Zrušit spárování', 'Pozice převzata z jiného zařízení', 'Přehrává se na: {name}', '{name} je nedostupné.', 'Hledám zařízení v síti…', 'toto zařízení', 'Spárovat zařízení (TV nebo PC)', 'Zkontrolovat aktualizace', 'Hledám aktualizace…', 'Stahuji verzi {v}…', 'Restartovat a aktualizovat na {v}', 'Verze {v} je připravená', 'Klikni pro restart. Jinak se nainstaluje při ukončení aplikace.', 'Máš nejnovější verzi ({v})', 'Aktualizace selhala: {t}', 'Přehrává se', 'zbývá {t}', 'Zpět na přehrávač', 'Je k dispozici verze {v}', 'Klikni a aplikace se sama nainstaluje a restartuje.', 'Stáhnout a nainstalovat verzi {v}', 'Aplikace ji stáhne, nainstaluje a znovu se spustí.', 'Aktualizovat a restartovat', 'Později', 'Zpět', 'Přehrát na tomto zařízení', 'Probouzím {name}… ({s} s)', '{name} se nezapnula – hraje se dál na tomto zařízení.'],
    sk: ['Otvoriť Audiobookshelf', 'Prehrať / pozastaviť', 'Zmeniť server…', 'Jazyk', 'Spúšťať po prihlásení do Windows', 'Ukončiť',
      'Otvoriť celé okno', 'Skryť', 'Predchádzajúca kapitola', 'Nasledujúca kapitola', 'Späť o {s} s', 'Dopredu o {s} s', 'Rýchlosť (klik = ďalšia, koliesko = jemne)', 'Časovač spánku', 'Hlasitosť (klik = stlmiť)', 'Kapitola {n}/{total}', 'Kniha',
      'Pokračovať v počúvaní', 'Prehrať', 'Server je nedostupný alebo sa stránka ešte načítava.', 'Načítavam prehrávač…', 'Prihláste sa v hlavnom okne.', 'Nič sa neprehráva.', 'Otvoriť Audiobookshelf',
      'Adresa servera Audiobookshelf', 'Napr. {a} alebo {b}', 'Uložiť a otvoriť', 'Neplatná adresa', 'Jazyk',
      'Server Audiobookshelf nie je dostupný', 'Skúsiť znova', 'Ďalší pokus automaticky o {n} s',
      'Kapitoly', 'Hľadať', 'Zavrieť zoznam',
      'Prehrávať na', 'Spárovať zariadenie', 'Párovací kód tohto PC: {code}', 'TV: otvor ABS Player, zobrazí kód. Telefón: v ABS Player zadaj kód tohto PC.', 'IP adresa', 'Port', 'Kód zo zariadenia', 'Heslo k ABS', 'Spárovať', 'Zariadenie nie je prihlásené k tvojmu účtu – zadaj heslo k ABS.', 'Nesprávne heslo.', 'Zariadenie neodpovedá.', 'Nedostupné', 'Pripravené', 'Hrá: {t}', 'Pozastavené: {t}', 'Zrušiť spárovanie', 'Pozícia prevzatá z iného zariadenia', 'Prehráva sa na: {name}', '{name} je nedostupné.', 'Hľadám zariadenia v sieti…', 'toto zariadenie', 'Spárovať zariadenie (TV alebo PC)', 'Skontrolovať aktualizácie', 'Hľadám aktualizácie…', 'Sťahujem verziu {v}…', 'Reštartovať a aktualizovať na {v}', 'Verzia {v} je pripravená', 'Klikni pre reštart. Inak sa nainštaluje pri ukončení aplikácie.', 'Máš najnovšiu verziu ({v})', 'Aktualizácia zlyhala: {t}', 'Prehráva sa', 'zostáva {t}', 'Späť na prehrávač', 'K dispozícii je verzia {v}', 'Klikni a aplikácia sa sama nainštaluje a reštartuje.', 'Stiahnuť a nainštalovať verziu {v}', 'Aplikácia ju stiahne, nainštaluje a znova sa spustí.', 'Aktualizovať a reštartovať', 'Neskôr', 'Späť', 'Prehrať na tomto zariadení', 'Prebúdzam {name}… ({s} s)', '{name} sa nezapol – hrá sa ďalej na tomto zariadení.'],
    en: ['Open Audiobookshelf', 'Play / pause', 'Change server…', 'Language', 'Start when I sign in to Windows', 'Quit',
      'Open full window', 'Hide', 'Previous chapter', 'Next chapter', 'Back {s} s', 'Forward {s} s', 'Speed (click = next, wheel = fine)', 'Sleep timer', 'Volume (click = mute)', 'Chapter {n}/{total}', 'Book',
      'Continue listening', 'Play', 'Server unavailable or the page is still loading.', 'Loading player…', 'Sign in in the main window.', 'Nothing is playing.', 'Open Audiobookshelf',
      'Audiobookshelf server address', 'e.g. {a} or {b}', 'Save and open', 'Invalid address', 'Language',
      'Audiobookshelf server is unavailable', 'Try again', 'Retrying automatically in {n} s',
      'Chapters', 'Search', 'Close list',
      'Play on', 'Pair a device', 'Pairing code of this PC: {code}', 'TV: open ABS Player, it shows a code. Phone: enter this PC’s code in ABS Player.', 'IP address', 'Port', 'Code from the device', 'ABS password', 'Pair', 'The device is not signed in to your account – enter your ABS password.', 'Wrong password.', 'The device does not respond.', 'Unavailable', 'Ready', 'Playing: {t}', 'Paused: {t}', 'Unpair', 'Position taken over from another device', 'Playing on: {name}', '{name} is unavailable.', 'Looking for devices on the network…', 'this device', 'Pair a device (TV or PC)', 'Check for updates', 'Checking for updates…', 'Downloading version {v}…', 'Restart and update to {v}', 'Version {v} is ready', 'Click to restart. Otherwise it installs when the app quits.', 'You have the latest version ({v})', 'Update failed: {t}', 'Playing now', '{t} left', 'Back to the player', 'Version {v} is available', 'Click and the app installs the update and restarts.', 'Download and install version {v}', 'The app downloads it, installs it and starts again.', 'Update and restart', 'Later', 'Back', 'Play on this device', 'Waking {name}… ({s} s)', '{name} did not turn on – playing on this device.'],
    de: ['Audiobookshelf öffnen', 'Wiedergabe / Pause', 'Server ändern…', 'Sprache', 'Bei der Windows-Anmeldung starten', 'Beenden',
      'Vollständiges Fenster öffnen', 'Ausblenden', 'Vorheriges Kapitel', 'Nächstes Kapitel', '{s} s zurück', '{s} s vor', 'Geschwindigkeit (Klick = nächste, Mausrad = fein)', 'Schlaftimer', 'Lautstärke (Klick = stumm)', 'Kapitel {n}/{total}', 'Buch',
      'Weiterhören', 'Abspielen', 'Server nicht erreichbar oder die Seite lädt noch.', 'Player wird geladen…', 'Bitte im Hauptfenster anmelden.', 'Es wird nichts abgespielt.', 'Audiobookshelf öffnen',
      'Adresse des Audiobookshelf-Servers', 'z. B. {a} oder {b}', 'Speichern und öffnen', 'Ungültige Adresse', 'Sprache',
      'Audiobookshelf-Server nicht erreichbar', 'Erneut versuchen', 'Nächster Versuch automatisch in {n} s',
      'Kapitel', 'Suchen', 'Liste schließen',
      'Wiedergeben auf', 'Gerät koppeln', 'Kopplungscode dieses PCs: {code}', 'TV: ABS Player öffnen, er zeigt einen Code. Telefon: in ABS Player den Code dieses PCs eingeben.', 'IP-Adresse', 'Port', 'Code vom Gerät', 'ABS-Passwort', 'Koppeln', 'Das Gerät ist nicht mit deinem Konto angemeldet – gib dein ABS-Passwort ein.', 'Falsches Passwort.', 'Das Gerät antwortet nicht.', 'Nicht erreichbar', 'Bereit', 'Spielt: {t}', 'Pausiert: {t}', 'Kopplung aufheben', 'Position von einem anderen Gerät übernommen', 'Wiedergabe auf: {name}', '{name} ist nicht erreichbar.', 'Suche Geräte im Netzwerk…', 'dieses Gerät', 'Gerät koppeln (TV oder PC)', 'Nach Updates suchen', 'Suche nach Updates…', 'Version {v} wird geladen…', 'Neu starten und auf {v} aktualisieren', 'Version {v} ist bereit', 'Zum Neustart klicken. Sonst wird sie beim Beenden installiert.', 'Du hast die neueste Version ({v})', 'Update fehlgeschlagen: {t}', 'Wird abgespielt', 'noch {t}', 'Zurück zum Player', 'Version {v} ist verfügbar', 'Klicken – die App installiert das Update und startet neu.', 'Version {v} herunterladen und installieren', 'Die App lädt sie herunter, installiert sie und startet neu.', 'Aktualisieren und neu starten', 'Später', 'Zurück', 'Auf diesem Gerät abspielen', '{name} wird aufgeweckt… ({s} s)', '{name} hat sich nicht eingeschaltet – Wiedergabe läuft auf diesem Gerät weiter.'],
    pl: ['Otwórz Audiobookshelf', 'Odtwórz / wstrzymaj', 'Zmień serwer…', 'Język', 'Uruchamiaj po zalogowaniu do Windows', 'Zakończ',
      'Otwórz pełne okno', 'Ukryj', 'Poprzedni rozdział', 'Następny rozdział', 'Wstecz o {s} s', 'Naprzód o {s} s', 'Prędkość (klik = następna, kółko = dokładnie)', 'Wyłącznik czasowy', 'Głośność (klik = wycisz)', 'Rozdział {n}/{total}', 'Książka',
      'Kontynuuj słuchanie', 'Odtwórz', 'Serwer niedostępny lub strona wciąż się ładuje.', 'Ładowanie odtwarzacza…', 'Zaloguj się w głównym oknie.', 'Nic nie jest odtwarzane.', 'Otwórz Audiobookshelf',
      'Adres serwera Audiobookshelf', 'Np. {a} lub {b}', 'Zapisz i otwórz', 'Nieprawidłowy adres', 'Język',
      'Serwer Audiobookshelf jest niedostępny', 'Spróbuj ponownie', 'Kolejna próba automatycznie za {n} s',
      'Rozdziały', 'Szukaj', 'Zamknij listę',
      'Odtwarzaj na', 'Sparuj urządzenie', 'Kod parowania tego PC: {code}', 'TV: otwórz ABS Player, pokaże kod. Telefon: wpisz w ABS Player kod tego PC.', 'Adres IP', 'Port', 'Kod z urządzenia', 'Hasło do ABS', 'Sparuj', 'Urządzenie nie jest zalogowane na Twoje konto – wpisz hasło do ABS.', 'Błędne hasło.', 'Urządzenie nie odpowiada.', 'Niedostępne', 'Gotowe', 'Odtwarza: {t}', 'Wstrzymano: {t}', 'Usuń parowanie', 'Pozycję przejęto z innego urządzenia', 'Odtwarzanie na: {name}', '{name} jest niedostępne.', 'Szukam urządzeń w sieci…', 'to urządzenie', 'Sparuj urządzenie (TV lub PC)', 'Sprawdź aktualizacje', 'Szukam aktualizacji…', 'Pobieram wersję {v}…', 'Uruchom ponownie i zaktualizuj do {v}', 'Wersja {v} jest gotowa', 'Kliknij, aby uruchomić ponownie. W przeciwnym razie zainstaluje się przy zamknięciu aplikacji.', 'Masz najnowszą wersję ({v})', 'Aktualizacja nie powiodła się: {t}', 'Odtwarzane', 'zostało {t}', 'Wróć do odtwarzacza', 'Dostępna jest wersja {v}', 'Kliknij, a aplikacja sama się zainstaluje i uruchomi ponownie.', 'Pobierz i zainstaluj wersję {v}', 'Aplikacja ją pobierze, zainstaluje i uruchomi się ponownie.', 'Aktualizuj i uruchom ponownie', 'Później', 'Wstecz', 'Odtwarzaj na tym urządzeniu', 'Wybudzam {name}… ({s} s)', '{name} się nie włączył – odtwarzanie trwa na tym urządzeniu.'],
    fr: ['Ouvrir Audiobookshelf', 'Lecture / pause', 'Changer de serveur…', 'Langue', 'Lancer à l’ouverture de session Windows', 'Quitter',
      'Ouvrir la fenêtre complète', 'Masquer', 'Chapitre précédent', 'Chapitre suivant', 'Reculer de {s} s', 'Avancer de {s} s', 'Vitesse (clic = suivante, molette = précis)', 'Minuteur de sommeil', 'Volume (clic = muet)', 'Chapitre {n}/{total}', 'Livre',
      'Reprendre l’écoute', 'Lire', 'Serveur indisponible ou page encore en chargement.', 'Chargement du lecteur…', 'Connectez-vous dans la fenêtre principale.', 'Aucune lecture en cours.', 'Ouvrir Audiobookshelf',
      'Adresse du serveur Audiobookshelf', 'Par ex. {a} ou {b}', 'Enregistrer et ouvrir', 'Adresse non valide', 'Langue',
      'Le serveur Audiobookshelf est indisponible', 'Réessayer', 'Nouvelle tentative automatique dans {n} s',
      'Chapitres', 'Rechercher', 'Fermer la liste',
      'Lire sur', 'Associer un appareil', 'Code d’association de ce PC : {code}', 'TV : ouvrez ABS Player, il affiche un code. Téléphone : saisissez le code de ce PC dans ABS Player.', 'Adresse IP', 'Port', 'Code de l’appareil', 'Mot de passe ABS', 'Associer', 'L’appareil n’est pas connecté à votre compte – saisissez votre mot de passe ABS.', 'Mot de passe incorrect.', 'L’appareil ne répond pas.', 'Indisponible', 'Prêt', 'Lecture : {t}', 'En pause : {t}', 'Dissocier', 'Position reprise d’un autre appareil', 'Lecture sur : {name}', '{name} est indisponible.', 'Recherche d’appareils sur le réseau…', 'cet appareil', 'Associer un appareil (TV ou PC)', 'Rechercher des mises à jour', 'Recherche de mises à jour…', 'Téléchargement de la version {v}…', 'Redémarrer et passer à la version {v}', 'La version {v} est prête', 'Cliquez pour redémarrer. Sinon, elle s’installera à la fermeture de l’application.', 'Vous avez la dernière version ({v})', 'Échec de la mise à jour : {t}', 'En lecture', 'reste {t}', 'Retour au lecteur', 'La version {v} est disponible', 'Cliquez : l’application s’installe et redémarre d’elle-même.', 'Télécharger et installer la version {v}', 'L’application la télécharge, l’installe et redémarre.', 'Mettre à jour et redémarrer', 'Plus tard', 'Retour', 'Lire sur cet appareil', 'Réveil de {name}… ({s} s)', '{name} ne s’est pas allumé – lecture sur cet appareil.'],
    es: ['Abrir Audiobookshelf', 'Reproducir / pausa', 'Cambiar servidor…', 'Idioma', 'Iniciar al iniciar sesión en Windows', 'Salir',
      'Abrir ventana completa', 'Ocultar', 'Capítulo anterior', 'Capítulo siguiente', 'Retroceder {s} s', 'Avanzar {s} s', 'Velocidad (clic = siguiente, rueda = ajuste fino)', 'Temporizador de apagado', 'Volumen (clic = silenciar)', 'Capítulo {n}/{total}', 'Libro',
      'Seguir escuchando', 'Reproducir', 'Servidor no disponible o la página aún se está cargando.', 'Cargando reproductor…', 'Inicia sesión en la ventana principal.', 'No se está reproduciendo nada.', 'Abrir Audiobookshelf',
      'Dirección del servidor Audiobookshelf', 'P. ej. {a} o {b}', 'Guardar y abrir', 'Dirección no válida', 'Idioma',
      'El servidor Audiobookshelf no está disponible', 'Reintentar', 'Nuevo intento automático en {n} s',
      'Capítulos', 'Buscar', 'Cerrar lista',
      'Reproducir en', 'Vincular dispositivo', 'Código de vinculación de este PC: {code}', 'TV: abre ABS Player, mostrará un código. Teléfono: introduce en ABS Player el código de este PC.', 'Dirección IP', 'Puerto', 'Código del dispositivo', 'Contraseña de ABS', 'Vincular', 'El dispositivo no ha iniciado sesión en tu cuenta: introduce tu contraseña de ABS.', 'Contraseña incorrecta.', 'El dispositivo no responde.', 'No disponible', 'Listo', 'Reproduciendo: {t}', 'En pausa: {t}', 'Desvincular', 'Posición tomada de otro dispositivo', 'Reproduciendo en: {name}', '{name} no está disponible.', 'Buscando dispositivos en la red…', 'este dispositivo', 'Vincular dispositivo (TV o PC)', 'Buscar actualizaciones', 'Buscando actualizaciones…', 'Descargando la versión {v}…', 'Reiniciar y actualizar a {v}', 'La versión {v} está lista', 'Haz clic para reiniciar. Si no, se instalará al cerrar la aplicación.', 'Tienes la última versión ({v})', 'Error al actualizar: {t}', 'Reproduciendo', 'quedan {t}', 'Volver al reproductor', 'Está disponible la versión {v}', 'Haz clic y la aplicación se instalará y reiniciará sola.', 'Descargar e instalar la versión {v}', 'La aplicación la descarga, la instala y se reinicia.', 'Actualizar y reiniciar', 'Más tarde', 'Atrás', 'Reproducir en este dispositivo', 'Despertando {name}… ({s} s)', '{name} no se encendió: se sigue reproduciendo en este dispositivo.'],
    it: ['Apri Audiobookshelf', 'Riproduci / pausa', 'Cambia server…', 'Lingua', 'Avvia all’accesso a Windows', 'Esci',
      'Apri finestra completa', 'Nascondi', 'Capitolo precedente', 'Capitolo successivo', 'Indietro di {s} s', 'Avanti di {s} s', 'Velocità (clic = successiva, rotellina = fine)', 'Timer di spegnimento', 'Volume (clic = muto)', 'Capitolo {n}/{total}', 'Libro',
      'Continua ad ascoltare', 'Riproduci', 'Server non disponibile o pagina ancora in caricamento.', 'Caricamento del lettore…', 'Accedi nella finestra principale.', 'Nessuna riproduzione in corso.', 'Apri Audiobookshelf',
      'Indirizzo del server Audiobookshelf', 'Ad es. {a} o {b}', 'Salva e apri', 'Indirizzo non valido', 'Lingua',
      'Il server Audiobookshelf non è disponibile', 'Riprova', 'Nuovo tentativo automatico tra {n} s',
      'Capitoli', 'Cerca', 'Chiudi elenco',
      'Riproduci su', 'Associa dispositivo', 'Codice di associazione di questo PC: {code}', 'TV: apri ABS Player, mostrerà un codice. Telefono: inserisci in ABS Player il codice di questo PC.', 'Indirizzo IP', 'Porta', 'Codice del dispositivo', 'Password di ABS', 'Associa', 'Il dispositivo non è connesso al tuo account: inserisci la password di ABS.', 'Password errata.', 'Il dispositivo non risponde.', 'Non disponibile', 'Pronto', 'In riproduzione: {t}', 'In pausa: {t}', 'Rimuovi associazione', 'Posizione ripresa da un altro dispositivo', 'In riproduzione su: {name}', '{name} non è disponibile.', 'Ricerca di dispositivi nella rete…', 'questo dispositivo', 'Associa dispositivo (TV o PC)', 'Controlla aggiornamenti', 'Ricerca aggiornamenti…', 'Download della versione {v}…', 'Riavvia e aggiorna a {v}', 'La versione {v} è pronta', 'Fai clic per riavviare. Altrimenti verrà installata alla chiusura dell’app.', 'Hai l’ultima versione ({v})', 'Aggiornamento non riuscito: {t}', 'In riproduzione', 'mancano {t}', 'Torna al lettore', 'È disponibile la versione {v}', 'Fai clic e l’app si installa e si riavvia da sola.', 'Scarica e installa la versione {v}', 'L’app la scarica, la installa e si riavvia.', 'Aggiorna e riavvia', 'Più tardi', 'Indietro', 'Riproduci su questo dispositivo', 'Risveglio di {name}… ({s} s)', '{name} non si è acceso: la riproduzione continua su questo dispositivo.'],
    pt: ['Abrir o Audiobookshelf', 'Reproduzir / pausar', 'Alterar servidor…', 'Idioma', 'Iniciar ao entrar no Windows', 'Sair',
      'Abrir janela completa', 'Ocultar', 'Capítulo anterior', 'Próximo capítulo', 'Recuar {s} s', 'Avançar {s} s', 'Velocidade (clique = próxima, roda = ajuste fino)', 'Temporizador de suspensão', 'Volume (clique = silenciar)', 'Capítulo {n}/{total}', 'Livro',
      'Continuar a ouvir', 'Reproduzir', 'Servidor indisponível ou a página ainda está a carregar.', 'A carregar o leitor…', 'Inicie sessão na janela principal.', 'Nada em reprodução.', 'Abrir o Audiobookshelf',
      'Endereço do servidor Audiobookshelf', 'Ex.: {a} ou {b}', 'Guardar e abrir', 'Endereço inválido', 'Idioma',
      'O servidor Audiobookshelf está indisponível', 'Tentar novamente', 'Nova tentativa automática em {n} s',
      'Capítulos', 'Pesquisar', 'Fechar lista',
      'Reproduzir em', 'Emparelhar dispositivo', 'Código de emparelhamento deste PC: {code}', 'TV: abra o ABS Player, ele mostra um código. Telemóvel: introduza no ABS Player o código deste PC.', 'Endereço IP', 'Porta', 'Código do dispositivo', 'Palavra-passe do ABS', 'Emparelhar', 'O dispositivo não tem sessão iniciada na sua conta – introduza a palavra-passe do ABS.', 'Palavra-passe errada.', 'O dispositivo não responde.', 'Indisponível', 'Pronto', 'A reproduzir: {t}', 'Em pausa: {t}', 'Desemparelhar', 'Posição obtida de outro dispositivo', 'A reproduzir em: {name}', '{name} está indisponível.', 'A procurar dispositivos na rede…', 'este dispositivo', 'Emparelhar dispositivo (TV ou PC)', 'Procurar atualizações', 'A procurar atualizações…', 'A transferir a versão {v}…', 'Reiniciar e atualizar para {v}', 'A versão {v} está pronta', 'Clique para reiniciar. Caso contrário, será instalada ao fechar a aplicação.', 'Tem a versão mais recente ({v})', 'A atualização falhou: {t}', 'A reproduzir', 'faltam {t}', 'Voltar ao leitor', 'A versão {v} está disponível', 'Clique e a aplicação instala-se e reinicia sozinha.', 'Transferir e instalar a versão {v}', 'A aplicação transfere-a, instala-a e reinicia.', 'Atualizar e reiniciar', 'Mais tarde', 'Voltar', 'Reproduzir neste dispositivo', 'A acordar {name}… ({s} s)', '{name} não ligou – a reprodução continua neste dispositivo.'],
    nl: ['Audiobookshelf openen', 'Afspelen / pauzeren', 'Server wijzigen…', 'Taal', 'Starten bij aanmelden bij Windows', 'Afsluiten',
      'Volledig venster openen', 'Verbergen', 'Vorig hoofdstuk', 'Volgend hoofdstuk', '{s} s terug', '{s} s vooruit', 'Snelheid (klik = volgende, scrollwiel = fijn)', 'Slaaptimer', 'Volume (klik = dempen)', 'Hoofdstuk {n}/{total}', 'Boek',
      'Verder luisteren', 'Afspelen', 'Server niet bereikbaar of de pagina wordt nog geladen.', 'Speler laden…', 'Meld je aan in het hoofdvenster.', 'Er wordt niets afgespeeld.', 'Audiobookshelf openen',
      'Adres van de Audiobookshelf-server', 'Bijv. {a} of {b}', 'Opslaan en openen', 'Ongeldig adres', 'Taal',
      'Audiobookshelf-server is niet bereikbaar', 'Opnieuw proberen', 'Automatisch opnieuw over {n} s',
      'Hoofdstukken', 'Zoeken', 'Lijst sluiten',
      'Afspelen op', 'Apparaat koppelen', 'Koppelcode van deze pc: {code}', 'Tv: open ABS Player, die toont een code. Telefoon: voer in ABS Player de code van deze pc in.', 'IP-adres', 'Poort', 'Code van het apparaat', 'ABS-wachtwoord', 'Koppelen', 'Het apparaat is niet aangemeld bij je account – voer je ABS-wachtwoord in.', 'Onjuist wachtwoord.', 'Het apparaat reageert niet.', 'Niet bereikbaar', 'Gereed', 'Speelt: {t}', 'Gepauzeerd: {t}', 'Ontkoppelen', 'Positie overgenomen van een ander apparaat', 'Speelt af op: {name}', '{name} is niet bereikbaar.', 'Apparaten zoeken in het netwerk…', 'dit apparaat', 'Apparaat koppelen (tv of pc)', 'Controleren op updates', 'Zoeken naar updates…', 'Versie {v} downloaden…', 'Herstarten en bijwerken naar {v}', 'Versie {v} staat klaar', 'Klik om te herstarten. Anders wordt hij geïnstalleerd bij het afsluiten.', 'Je hebt de nieuwste versie ({v})', 'Bijwerken mislukt: {t}', 'Speelt nu', 'nog {t}', 'Terug naar de speler', 'Versie {v} is beschikbaar', 'Klik en de app installeert de update en herstart.', 'Versie {v} downloaden en installeren', 'De app downloadt en installeert hem en start opnieuw.', 'Bijwerken en herstarten', 'Later', 'Terug', 'Afspelen op dit apparaat', '{name} wordt gewekt… ({s} s)', '{name} ging niet aan – het afspelen gaat verder op dit apparaat.'],
    hu: ['Audiobookshelf megnyitása', 'Lejátszás / szünet', 'Szerver módosítása…', 'Nyelv', 'Indítás a Windowsba való bejelentkezéskor', 'Kilépés',
      'Teljes ablak megnyitása', 'Elrejtés', 'Előző fejezet', 'Következő fejezet', '{s} mp vissza', '{s} mp előre', 'Sebesség (kattintás = következő, görgő = finom)', 'Elalvási időzítő', 'Hangerő (kattintás = némítás)', 'Fejezet {n}/{total}', 'Könyv',
      'Hallgatás folytatása', 'Lejátszás', 'A szerver nem érhető el, vagy az oldal még töltődik.', 'Lejátszó betöltése…', 'Jelentkezz be a főablakban.', 'Nincs lejátszás.', 'Audiobookshelf megnyitása',
      'Az Audiobookshelf-szerver címe', 'Pl. {a} vagy {b}', 'Mentés és megnyitás', 'Érvénytelen cím', 'Nyelv',
      'Az Audiobookshelf-szerver nem érhető el', 'Újra', 'Automatikus újrapróbálás {n} mp múlva',
      'Fejezetek', 'Keresés', 'Lista bezárása',
      'Lejátszás itt', 'Eszköz párosítása', 'A gép párosítási kódja: {code}', 'TV: nyisd meg az ABS Playert, kódot mutat. Telefon: az ABS Playerben add meg ennek a gépnek a kódját.', 'IP-cím', 'Port', 'Az eszköz kódja', 'ABS-jelszó', 'Párosítás', 'Az eszköz nincs bejelentkezve a fiókodba – add meg az ABS-jelszót.', 'Hibás jelszó.', 'Az eszköz nem válaszol.', 'Nem érhető el', 'Kész', 'Lejátszás: {t}', 'Szüneteltetve: {t}', 'Párosítás törlése', 'A pozíció átvéve egy másik eszközről', 'Lejátszás itt: {name}', '{name} nem érhető el.', 'Eszközök keresése a hálózaton…', 'ez az eszköz', 'Eszköz párosítása (TV vagy PC)', 'Frissítések keresése', 'Frissítések keresése…', 'A(z) {v} verzió letöltése…', 'Újraindítás és frissítés erre: {v}', 'A(z) {v} verzió készen áll', 'Kattints az újraindításhoz. Különben kilépéskor települ.', 'A legújabb verzió van telepítve ({v})', 'A frissítés sikertelen: {t}', 'Most szól', 'még {t}', 'Vissza a lejátszóhoz', 'Elérhető a(z) {v} verzió', 'Kattints, és az alkalmazás magától települ és újraindul.', 'A(z) {v} verzió letöltése és telepítése', 'Az alkalmazás letölti, telepíti és újraindul.', 'Frissítés és újraindítás', 'Később', 'Vissza', 'Lejátszás ezen az eszközön', '{name} ébresztése… ({s} mp)', '{name} nem kapcsolt be – a lejátszás ezen az eszközön folytatódik.'],
    uk: ['Відкрити Audiobookshelf', 'Відтворити / пауза', 'Змінити сервер…', 'Мова', 'Запускати під час входу у Windows', 'Вийти',
      'Відкрити повне вікно', 'Сховати', 'Попередній розділ', 'Наступний розділ', 'Назад на {s} с', 'Вперед на {s} с', 'Швидкість (клік = наступна, коліщатко = точно)', 'Таймер сну', 'Гучність (клік = вимкнути звук)', 'Розділ {n}/{total}', 'Книга',
      'Продовжити слухати', 'Відтворити', 'Сервер недоступний або сторінка ще завантажується.', 'Завантаження плеєра…', 'Увійдіть у головному вікні.', 'Нічого не відтворюється.', 'Відкрити Audiobookshelf',
      'Адреса сервера Audiobookshelf', 'Напр. {a} або {b}', 'Зберегти й відкрити', 'Недійсна адреса', 'Мова',
      'Сервер Audiobookshelf недоступний', 'Спробувати ще раз', 'Наступна спроба автоматично через {n} с',
      'Розділи', 'Пошук', 'Закрити список',
      'Відтворювати на', 'Спарувати пристрій', 'Код спарювання цього ПК: {code}', 'ТВ: відкрийте ABS Player, він покаже код. Телефон: введіть в ABS Player код цього ПК.', 'IP-адреса', 'Порт', 'Код із пристрою', 'Пароль ABS', 'Спарувати', 'Пристрій не ввійшов у ваш обліковий запис – введіть пароль ABS.', 'Неправильний пароль.', 'Пристрій не відповідає.', 'Недоступно', 'Готово', 'Відтворюється: {t}', 'Пауза: {t}', 'Скасувати спарювання', 'Позицію взято з іншого пристрою', 'Відтворюється на: {name}', '{name} недоступний.', 'Пошук пристроїв у мережі…', 'цей пристрій', 'Підключити пристрій (ТБ або ПК)', 'Перевірити оновлення', 'Пошук оновлень…', 'Завантаження версії {v}…', 'Перезапустити й оновити до {v}', 'Версія {v} готова', 'Натисніть, щоб перезапустити. Інакше встановиться під час закриття застосунку.', 'У вас найновіша версія ({v})', 'Не вдалося оновити: {t}', 'Відтворюється', 'залишилось {t}', 'Назад до плеєра', 'Доступна версія {v}', 'Натисніть, і застосунок сам встановить оновлення й перезапуститься.', 'Завантажити й встановити версію {v}', 'Застосунок завантажить її, встановить і перезапуститься.', 'Оновити й перезапустити', 'Пізніше', 'Назад', 'Відтворювати на цьому пристрої', 'Пробуджую {name}… ({s} с)', '{name} не ввімкнувся – відтворення триває на цьому пристрої.'],
    ru: ['Открыть Audiobookshelf', 'Воспроизвести / пауза', 'Сменить сервер…', 'Язык', 'Запускать при входе в Windows', 'Выход',
      'Открыть полное окно', 'Скрыть', 'Предыдущая глава', 'Следующая глава', 'Назад на {s} с', 'Вперёд на {s} с', 'Скорость (клик = следующая, колесо = точно)', 'Таймер сна', 'Громкость (клик = без звука)', 'Глава {n}/{total}', 'Книга',
      'Продолжить прослушивание', 'Воспроизвести', 'Сервер недоступен или страница ещё загружается.', 'Загрузка плеера…', 'Войдите в главном окне.', 'Ничего не воспроизводится.', 'Открыть Audiobookshelf',
      'Адрес сервера Audiobookshelf', 'Напр. {a} или {b}', 'Сохранить и открыть', 'Недопустимый адрес', 'Язык',
      'Сервер Audiobookshelf недоступен', 'Повторить', 'Следующая попытка автоматически через {n} с',
      'Главы', 'Поиск', 'Закрыть список',
      'Воспроизводить на', 'Связать устройство', 'Код связывания этого ПК: {code}', 'ТВ: откройте ABS Player, он покажет код. Телефон: введите в ABS Player код этого ПК.', 'IP-адрес', 'Порт', 'Код с устройства', 'Пароль ABS', 'Связать', 'Устройство не вошло в ваш аккаунт – введите пароль ABS.', 'Неверный пароль.', 'Устройство не отвечает.', 'Недоступно', 'Готово', 'Воспроизводится: {t}', 'Пауза: {t}', 'Отвязать', 'Позиция взята с другого устройства', 'Воспроизводится на: {name}', '{name} недоступно.', 'Поиск устройств в сети…', 'это устройство', 'Подключить устройство (ТВ или ПК)', 'Проверить обновления', 'Поиск обновлений…', 'Загрузка версии {v}…', 'Перезапустить и обновить до {v}', 'Версия {v} готова', 'Нажмите, чтобы перезапустить. Иначе установится при закрытии приложения.', 'У вас последняя версия ({v})', 'Не удалось обновить: {t}', 'Воспроизводится', 'осталось {t}', 'Назад к плееру', 'Доступна версия {v}', 'Нажмите, и приложение само установит обновление и перезапустится.', 'Скачать и установить версию {v}', 'Приложение скачает её, установит и перезапустится.', 'Обновить и перезапустить', 'Позже', 'Назад', 'Воспроизводить на этом устройстве', 'Пробуждаю {name}… ({s} с)', '{name} не включился – воспроизведение продолжается на этом устройстве.'],
    zh: ['打开 Audiobookshelf', '播放 / 暂停', '更改服务器…', '语言', '登录 Windows 时启动', '退出',
      '打开完整窗口', '隐藏', '上一章', '下一章', '后退 {s} 秒', '前进 {s} 秒', '速度（单击 = 下一档，滚轮 = 微调）', '睡眠定时器', '音量（单击 = 静音）', '第 {n}/{total} 章', '整本书',
      '继续收听', '播放', '服务器不可用或页面仍在加载。', '正在加载播放器…', '请在主窗口中登录。', '当前没有播放。', '打开 Audiobookshelf',
      'Audiobookshelf 服务器地址', '例如 {a} 或 {b}', '保存并打开', '地址无效', '语言',
      'Audiobookshelf 服务器不可用', '重试', '{n} 秒后自动重试',
      '章节', '搜索', '关闭列表',
      '播放到', '配对设备', '本机配对码：{code}', '电视：打开 ABS Player，会显示代码。手机：在 ABS Player 中输入本机的代码。', 'IP 地址', '端口', '设备上的代码', 'ABS 密码', '配对', '该设备未登录你的账户，请输入 ABS 密码。', '密码错误。', '设备无响应。', '不可用', '就绪', '正在播放：{t}', '已暂停：{t}', '取消配对', '已从其他设备接管播放位置', '正在播放于：{name}', '{name} 不可用。', '正在搜索网络中的设备…', '本设备', '配对设备（电视或电脑）', '检查更新', '正在检查更新…', '正在下载 {v} 版本…', '重启并更新到 {v}', '{v} 版本已就绪', '点击以重启。否则将在退出应用时安装。', '已是最新版本（{v}）', '更新失败：{t}', '正在播放', '剩余 {t}', '返回播放器', '{v} 版本可用', '点击后应用将自动安装并重启。', '下载并安装 {v} 版本', '应用将下载、安装并重新启动。', '更新并重启', '稍后', '返回', '在此设备上播放', '正在唤醒 {name}…（{s} 秒）', '{name} 未开机 – 继续在此设备上播放。'],
    ja: ['Audiobookshelf を開く', '再生 / 一時停止', 'サーバーを変更…', '言語', 'Windows へのサインイン時に開始', '終了',
      'フルウィンドウを開く', '隠す', '前のチャプター', '次のチャプター', '{s} 秒戻る', '{s} 秒進む', '速度（クリック = 次、ホイール = 微調整）', 'スリープタイマー', '音量（クリック = ミュート）', 'チャプター {n}/{total}', '本全体',
      '続きを聴く', '再生', 'サーバーに接続できないか、ページを読み込み中です。', 'プレーヤーを読み込み中…', 'メインウィンドウでログインしてください。', '再生中のものはありません。', 'Audiobookshelf を開く',
      'Audiobookshelf サーバーのアドレス', '例: {a} または {b}', '保存して開く', '無効なアドレス', '言語',
      'Audiobookshelf サーバーに接続できません', '再試行', '{n} 秒後に自動で再試行',
      'チャプター', '検索', 'リストを閉じる',
      '再生先', 'デバイスをペアリング', 'このPCのペアリングコード: {code}', 'テレビ: ABS Player を開くとコードが表示されます。スマホ: ABS Player にこのPCのコードを入力します。', 'IP アドレス', 'ポート', 'デバイスのコード', 'ABS のパスワード', 'ペアリング', 'このデバイスはあなたのアカウントにログインしていません。ABS のパスワードを入力してください。', 'パスワードが違います。', 'デバイスが応答しません。', '利用不可', '準備完了', '再生中: {t}', '一時停止: {t}', 'ペアリング解除', '別のデバイスから再生位置を引き継ぎました', '再生先: {name}', '{name} は利用できません。', 'ネットワーク上のデバイスを検索中…', 'このデバイス', 'デバイスをペアリング（テレビまたは PC）', 'アップデートを確認', 'アップデートを確認中…', 'バージョン {v} をダウンロード中…', '再起動して {v} に更新', 'バージョン {v} の準備ができました', 'クリックすると再起動します。そうでなければアプリ終了時にインストールされます。', '最新バージョンです（{v}）', 'アップデートに失敗しました：{t}', '再生中', '残り {t}', 'プレーヤーに戻る', 'バージョン {v} が利用可能です', 'クリックするとアプリが自動でインストールして再起動します。', 'バージョン {v} をダウンロードしてインストール', 'アプリがダウンロードしてインストールし、再起動します。', '更新して再起動', '後で', '戻る', 'このデバイスで再生', '{name} を起動中…（{s} 秒）', '{name} が起動しませんでした – このデバイスで再生を続けます。']
  };

  const STRINGS = {};
  for (const code of Object.keys(T)) {
    if (T[code].length !== KEYS.length) throw new Error('i18n: ' + code + ' má ' + T[code].length + ' textů, čekáno ' + KEYS.length);
    STRINGS[code] = {};
    KEYS.forEach((k, i) => { STRINGS[code][k] = T[code][i]; });
  }

  function normalize(locale) {
    const l = String(locale || '').toLowerCase();
    if (STRINGS[l]) return l;
    const base = l.split(/[-_]/)[0];
    return STRINGS[base] ? base : 'en';
  }

  function tr(lang, key, vars) {
    let s = (STRINGS[lang] && STRINGS[lang][key]) || STRINGS.en[key] || key;
    if (vars) for (const k of Object.keys(vars)) s = s.split('{' + k + '}').join(String(vars[k]));
    return s;
  }

  const api = { LANGS, FLAGS, STRINGS, KEYS, normalize, tr };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.I18N = api;
})(typeof window !== 'undefined' ? window : globalThis);
