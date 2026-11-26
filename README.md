# Rosenweg Commons

Gemeinsame Bibliothek der Rosenweg-Apps für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Sie besteht aus dem Fork der Fossify Commons, gegen den alle Rosenweg-Fossify-Apps bauen, und zwei eigenen Modulen für die Rosenweg-Infrastruktur. Diese Bibliothek ist keine App und wird nicht installiert.

## Module

- **`commons`** (`org.fossify:commons`, Rosenweg-Fassung `7.1.3-rosenweg.1`): Fossify Commons ohne Spenden- und Social-Kanäle, für eigene Paketnamen `ch.rosenweg4303.*`.
- **`cloud`** (`ch.rosenweg4303:cloud`): Zugang zur Rosenweg-Nextcloud über WebDAV. Anmeldung per Nextcloud-Login (App-Passwort) oder mit dem Rosenweg-Konto (öffentlicher Client, PKCE; das Refresh-Token liegt verschlüsselt im Android-Keystore). Auf geteilten Telefonen hat der Zugang von Rosenweg Gerät Vorrang. Dazu: Upload-Warteschlange (lokal gelöscht wird erst nach Bestätigung), Hinweis beim ersten Start, Auflisten und Streamen der eigenen Dateien und Einstellungen pro Person in `Persönlich/.rosenweg/`. Bindet `updater` ein.
- **`updater`** (`ch.rosenweg4303:updater`): Rosenweg Updater. Auf Handys ohne Rosenweg Gerät prüft genau eine installierte Rosenweg-App den App-Katalog der Telefonanlage und aktualisiert alle installierten Rosenweg-Apps. Jede APK wird vor der Installation geprüft (Grösse, SHA-256, Paketname, Version, Signatur gleich der eigenen); Android lässt jede Installation bestätigen.

## Verwendung

Die Rosenweg-Apps holen die Bibliothek aus dem lokalen Maven-Repository (`mavenLocal()`):

```bash
# Zweig rosenweg
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal
```

Das veröffentlicht `org.fossify:commons:7.1.3-rosenweg.1` sowie `ch.rosenweg4303:cloud` und `ch.rosenweg4303:updater` (Versionen in `cloud/build.gradle.kts` und `updater/build.gradle.kts`, überschreibbar mit `-PCLOUD_VERSION=…` bzw. `-PUPDATER_VERSION=…`). Danach die Apps bauen, z.B. `./gradlew assembleFossRelease`.

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

## Herkunft und Lizenz

Fork von [Fossify Commons](https://github.com/FossifyOrg/commons). Der Zweig `main` folgt dem Original, die Rosenweg-Anpassungen und die Module `cloud` und `updater` liegen im Zweig `rosenweg`. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
