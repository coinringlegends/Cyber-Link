# CYBER LINK – Offline Bluetooth Messenger, Telefonie & Offline-Karten

**CYBER LINK** ist eine vollständig offline arbeitende Android-App für Ende-zu-Ende-verschlüsselte Kommunikation über **Bluetooth Low Energy (BLE GATT)** und **Bluetooth Classic (RFCOMM)** – ohne Internet, ohne Server, ohne Mobilfunknetz und ohne WLAN.

> **Direkte Download-Webseite (GitHub Pages):**  
> Nach Aktivierung von GitHub Pages auf diesem Repository (`Settings -> Pages -> Branch: main -> Folder: /docs`) ist die Download- und Anleitungsseite direkt unter `https://<dein-github-name>.github.io/<repo-name>/` erreichbar.  
> Die fertig gebaute APK liegt direkt im Repository unter [`docs/downloads/cyber-link-app.apk`](docs/downloads/cyber-link-app.apk).

---

## Wofür ist CYBER LINK gedacht?

- **Kommunikation ohne Netz:** In Funklöchern, Tunneln, Kellern, auf Baustellen, Festivals oder Wanderungen.
- **Notfall- & Krisenvorsorge:** Funktioniert direkt zwischen Android-Smartphones ohne zentrale Infrastruktur.
- **Datenschutz durch Technik:** Keine Registrierung, keine Telefonnummer, keine Cloud und technisch **keine `INTERNET`-Berechtigung** im Android-Manifest.

---

## Was die App kann

1. **Kryptografische Identität & 12-Stufen-QR-Pairing**
   - Lokales EC P-256 Schlüsselpaar (`EncryptionManager`) mit stabiler Node-ID (`CL-...`).
   - Signierte, zeitlich begrenzte Einmal-QR-Codes (ohne private Schlüssel) mit 12-stufigem Kopplungsprozess und gegenseitiger Bestätigung auf beiden Smartphones.
   - Echtzeit-BLE-Diagnose (`Advertising`, `Scanner`, erkannte Service-UUID, Node-IDs, GATT-Status, Android-Fehlercodes).
2. **Verschlüsselte Einzel- & Gruppenchats + Dateien**
   - AES-256-GCM Transportverschlüsselung + verschlüsselte lokale Room-Speicherung (`ENC1:`).
   - Echte Statusanzeige: `QUEUED` → `SENT` → `DELIVERED` (Zustellbestätigung) → `READ` (Lesebestätigung).
   - Offline-Nachrichtenwarteschlange mit automatischem Nachsenden bei Wiederverbindung.
   - Echter Bild- & Dateiversand über Android Photo Picker / SAF mit SHA-256-Integritätsprüfung.
3. **Echte Bluetooth-Telefonie & Videoanrufe**
   - **Audio:** Bidirektional über `AudioRecord`/`AudioTrack` (8 kHz 16-bit PCM) via RFCOMM inkl. Annehmen, Ablehnen, Stummschaltung, Lautsprecher und Bitraten-/Verlustanzeige.
   - **Video:** Beidseitiges CameraX-Video (adaptives JPEG über RFCOMM) mit Front/Back-Kamerawechsel und FPS/kbps-Anzeige.
   - **Ehrliche Limits:** BLE GATT allein reicht nicht für Echtzeit-Audio/Video; ohne aktiven RFCOMM-Kanal sowie bei Gruppenanrufen (3+ Geräte) meldet die App ehrlich `NICHT UNTERSTÜTZT`.
4. **CYBER MAP & freiwilliges Mesh-Relay**
   - Offline-Mapsforge-Vektorkarten (`.map`), echtes GNSS/GPS, Offline-Suche, A*-Routing, Wegpunkte und Karten-Sharing per Bluetooth.
   - Opt-in-Standortfreigabe nur für bestätigte Kontakte mit Not-Aus-Killswitch.
   - Freiwilliges Store-and-Forward-Mesh-Relay für Nachrichten (niemals für Standorte).

---

## APK direkt auf Android installieren

1. Öffne die [GitHub-Pages-Downloadseite](docs/index.html) oder lade direkt [`docs/downloads/cyber-link-app.apk`](docs/downloads/cyber-link-app.apk) auf dein Android-Smartphone herunter.
2. Öffne die `.apk`-Datei im Browser oder Dateimanager und erlaube bei Nachfrage *„Apps aus dieser Quelle installieren“*.
3. Erteile beim Start die Bluetooth-Berechtigungen (*Geräte in der Nähe*).
4. Auf Gerät B unter **Netz → Mein QR** den QR-Code öffnen und auf Gerät A über **QR scannen** verbinden.

---

## GitHub Pages & automatischen Build einrichten

### Option A: Sofort über `/(root)` oder `/docs` (ohne Build-Minuten)
1. Repository auf GitHub pushen.
2. Auf GitHub **Settings → Pages** öffnen.
3. Unter **Build and deployment** → Source: **Deploy from a branch** → Branch **`main`**, Ordner **`/docs`** (oder **`/(root)`**, da sowohl `docs/index.html` als auch `index.html` im Hauptverzeichnis liegen) auswählen und speichern.
4. Nach ca. 1 Minute ist die Webseite inkl. direktem APK-Download online.

### Option B: Automatischer APK-Neubau per GitHub Actions
Der beiliegende Workflow [`.github/workflows/github-pages-apk.yml`](.github/workflows/github-pages-apk.yml) baut bei jedem Push auf `main` automatisch die Unit-/Robolectric-Tests sowie die Debug-APK, aktualisiert `docs/downloads/cyber-link-app.apk` und deployt die Seite auf GitHub Pages (falls unter *Settings → Pages* Source auf **GitHub Actions** umgestellt wird).

---

## Technische Eckdaten & Softwaretests

- **Application ID:** `com.aistudio.cyberlink.kxmpzq`
- **Min / Target SDK:** Android 7.0 (API 24) / API 36
- **APK SHA-256:** `6c3df7f77e5dd55fd0fe4d35314bd21a842ef95c51909dceeb83c2030d78ff34`
- **Software- & Robolectric-Tests:** 42 / 42 bestanden (`gradle :app:testDebugUnitTest`)
- **Physischer Zwei-Smartphone-Hardwaretest:** Noch offen (`NICHT GETESTET` in der Cloud-Build-Umgebung)
