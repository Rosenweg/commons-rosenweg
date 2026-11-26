# Rosenweg Commons – Für Entwickler

Gemeinsame Bibliothek der Rosenweg-Apps für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Sie besteht aus dem Fork der Fossify Commons, gegen den alle Rosenweg-Fossify-Apps bauen, und eigenen Modulen für die Rosenweg-Infrastruktur. Diese Bibliothek ist keine App und wird nicht installiert.

## Module

- **`commons`** (`org.fossify:commons`, Rosenweg-Fassung `7.1.3-rosenweg.1`): Fossify Commons ohne Spenden- und Social-Kanäle, für eigene Paketnamen `ch.rosenweg4303.*`.
- **`cloud`** (`ch.rosenweg4303:cloud`): Zugang zur Rosenweg-Nextcloud über WebDAV. Anmeldung per Nextcloud-Login (App-Passwort) oder mit dem Rosenweg-Konto (öffentlicher Client, PKCE; das Refresh-Token liegt verschlüsselt im Android-Keystore). Auf geteilten Telefonen hat der Zugang von Rosenweg Gerät Vorrang. Dazu: Upload-Warteschlange (lokal gelöscht wird erst nach Bestätigung), Hinweis beim ersten Start, Auflisten und Streamen der eigenen Dateien und Einstellungen pro Person in `Persönlich/.rosenweg/`. Bindet `updater` ein.
- **`anmeldeschirm`** (`ch.rosenweg4303:anmeldeschirm`): Aussehen, Texte, Schritt-Zeilen und Kartenleser des Rosenweg-Anmeldeschirms. Sie stammen aus dem Sperrbildschirm von Rosenweg Gerät und werden dort wieder benutzt; dazu kommen der Anmeldeschirm der Apps (`cloud`) und die Flutter-Apps über `rosenweg_basis`. Das Modul geht nicht ins Netz und hat minSdk 21.
- **`updater`** (`ch.rosenweg4303:updater`): Rosenweg Updater. Auf Handys ohne Rosenweg Gerät prüft genau eine installierte Rosenweg-App den App-Katalog der Telefonanlage und aktualisiert alle installierten Rosenweg-Apps. Jede APK wird vor der Installation geprüft (Grösse, SHA-256, Paketname, Version, Signatur gleich der eigenen); Android lässt jede Installation bestätigen.

## Anmeldeschirm (privates Handy)

Auf Handys ohne Rosenweg Gerät zeigt jede Rosenweg-App mit Anmeldung den gemeinsamen Anmeldeschirm `ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm`. Er sieht aus wie der Sperrbildschirm der geteilten Telefone. Man meldet sich dort auf einem von zwei Wegen an:

- **Rosenweg-Karte hinten ans Telefon halten.** Die Karte allein meldet an, ohne PIN. Die App erhält dann eine eigene Karten-Sitzung, die an das Gerät gebunden ist (Schlüssel im Android-Keystore, siehe `KartenSitzung`).
- **„Mit Rosenweg-Konto anmelden“** im Browser.

Jede App meldet sich selbst an, und Abmelden betrifft nur diese App. So bindet eine App den Schirm ein:

```kotlin
override fun onResume() {
    super.onResume()
    if (RosenwegAnmeldeSchirm.zeigenFallsNoetig(this)) return   // Java: RosenwegAnmeldeSchirm.zeigenFallsNoetig(this)
    // ab hier angemeldet: RosenwegAnmeldung.zugangsToken(this) gilt für beide Wege
}
```

`RosenwegAnmeldung.angemeldet`, `name`, `zugangsToken`, `zugangUngueltig` und `abmelden` decken beide Wege ab. Server: Website `api/routes/app-karte.js`.

## Verwendung

Die Rosenweg-Apps holen die Bibliothek aus dem lokalen Maven-Repository (`mavenLocal()`):

```bash
# Zweig main
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal
```

Das veröffentlicht `org.fossify:commons:7.1.3-rosenweg.1` sowie `ch.rosenweg4303:cloud`, `ch.rosenweg4303:anmeldeschirm` und `ch.rosenweg4303:updater` (Versionen in `cloud/build.gradle.kts` und `updater/build.gradle.kts`, überschreibbar mit `-PCLOUD_VERSION=…` bzw. `-PUPDATER_VERSION=…`). Danach die Apps bauen, z.B. `./gradlew assembleFossRelease`.

### Über GitHub Packages

Bei jedem Tag `v*` veröffentlicht der Workflow `.github/workflows/pakete.yml` alle Module nach GitHub Packages (Maven): `https://maven.pkg.github.com/Rosenweg/commons-rosenweg`.
- `org.fossify:commons` bekommt als Version den Tag ohne `v`.
- `ch.rosenweg4303:cloud`, `anmeldeschirm` und `updater` behalten die Versionen aus ihren `build.gradle.kts`.
- Eine schon vorhandene Version wird übersprungen.

Auch öffentliche Maven-Pakete brauchen zum Herunterladen eine Anmeldung. Die Apps lesen sie so:
- lokal mit `gpr.user` und `gpr.key` aus `~/.gradle/gradle.properties`. `gpr.key` ist ein Token mit `read:packages`, z. B. `gh auth token`.
- in GitHub Actions mit `GPR_TOKEN` (Org-Secret) oder `GITHUB_TOKEN`.

`mavenLocal()` bleibt als Rückfall.

Die Apps selbst gibt es unter [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps).

## Zweige, Herkunft und Lizenz

Fork von [Fossify Commons](https://github.com/FossifyOrg/commons). Die Rosenweg-Anpassungen und die eigenen Module (früher Zweig `rosenweg`) sind in `main` gemergt; `main` ist der Hauptzweig. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](../LICENSE).
