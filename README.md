<p align="center"><img src="docs/logo.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Commons</h1>
<p align="center">Gemeinsame Bibliothek der Rosenweg-Android-Apps – keine App, wird nicht installiert.</p>
<p align="center"><a href="docs/entwickeln.md">Doku</a> · <a href="https://www.rosenweg4303.ch/apps">Die Apps</a></p>

## Was drin ist

- **`commons`** – Fossify Commons in der Rosenweg-Fassung, Grundlage aller Rosenweg-Fossify-Apps.
- **`cloud`** – Zugang zur Rosenweg-Nextcloud: Anmeldung, Upload-Warteschlange, Einstellungen pro Person.
- **`updater`** – Rosenweg Updater: hält auf eigenen Handys alle Rosenweg-Apps aktuell, mit Prüfung jeder APK.

## Entwickeln

Android-Bibliothek (Kotlin, Gradle). `./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal`
veröffentlicht alle drei Module für die Apps – Einzelheiten in [docs/entwickeln.md](docs/entwickeln.md).

## Herkunft und Lizenz

Basiert auf [Fossify Commons](https://github.com/FossifyOrg/commons) – herzlichen Dank an das Fossify-Team.
GPL-3.0, siehe [LICENSE](LICENSE).
