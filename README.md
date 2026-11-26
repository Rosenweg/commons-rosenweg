<p align="center"><img src="docs/logo.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Commons</h1>
<p align="center">Gemeinsame Bibliothek der Rosenweg-Android-Apps – keine App, wird nicht installiert.</p>
<p align="center"><a href="docs/entwickeln.md">Doku</a> · <a href="https://www.rosenweg4303.ch/apps">Die Apps</a></p>

## Inhalt

- [Überblick](#überblick)
- [Was drin ist](#was-drin-ist)
- [Läuft auf](#läuft-auf)
- [Anmeldeschirm (eigenes Handy)](#anmeldeschirm-eigenes-handy)
- [Rosenweg Updater](#rosenweg-updater)
- [Aufbau](#aufbau)
- [Bauen und verwenden](#bauen-und-verwenden)
- [CI und Pakete](#ci-und-pakete)
- [Konfiguration](#konfiguration)
- [Herkunft und Lizenz](#herkunft-und-lizenz)

## Überblick

Rosenweg Commons ist der Fork der Fossify Commons, gegen den alle Rosenweg-Fossify-Apps bauen (Kamera, Telefon, Messenger …), dazu eigene Module für die Rosenweg-Infrastruktur: Nextcloud, gemeinsamer Anmeldeschirm und Updater. Was hier einmal gelöst ist, muss keine App nachbauen.

## Was drin ist

| Modul | Koordinate (Version) | Inhalt |
|---|---|---|
| `commons` | `org.fossify:commons` (`7.1.3-rosenweg.1`) | Fossify Commons ohne Spenden- und Social-Kanäle, für eigene Paketnamen `ch.rosenweg4303.*` |
| `cloud` | `ch.rosenweg4303:cloud` (0.9.0) | Rosenweg-Nextcloud über WebDAV; Anmeldung (Anmeldeschirm, Rosenweg-Konto mit PKCE, Nextcloud-Login); Upload-Warteschlange (lokal gelöscht erst nach Bestätigung); eigene Dateien auflisten und streamen; Einstellungen pro Person in `Persönlich/.rosenweg/`; CalDAV und CardDAV ohne DAVx⁵. Bindet `updater` ein. |
| `anmeldeschirm` | `ch.rosenweg4303:anmeldeschirm` (0.1.0) | Aussehen, Texte, Schritt-Zeilen und Kartenleser des Rosenweg-Anmeldeschirms – derselbe wie der Sperrbildschirm von Rosenweg Gerät. Ohne Netz, minSdk 21. |
| `updater` | `ch.rosenweg4303:updater` (0.1.2) | Rosenweg Updater: hält auf eigenen Handys alle Rosenweg-Apps aktuell |

Auf geteilten Telefonen hat der Zugang von Rosenweg Gerät immer Vorrang.

## Läuft auf

| Wo | Stand |
|---|---|
| Android 8 und neuer (API 26–36) | `commons`, `cloud`, `updater` |
| Android 5 und neuer (API 21) | `anmeldeschirm` (auch in Rosenweg Gerät) |
| Flutter-Apps | nutzen den Anmeldeschirm über `rosenweg_basis` |

## Anmeldeschirm (eigenes Handy)

Auf Handys ohne Rosenweg Gerät zeigt jede Rosenweg-App mit Backend beim Öffnen den gemeinsamen Anmeldeschirm `ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm`. Er sieht aus wie der Sperrbildschirm der geteilten Telefone. Ohne Anmeldung ist die App nicht nutzbar.

| Weg | So geht’s |
|---|---|
| Rosenweg-Karte | hinten ans Telefon halten (NFC). Die Karte allein meldet an, ohne PIN – die PIN gibt es nur auf geteilten Telefonen. Die App erhält eine eigene Karten-Sitzung, gebunden ans Gerät (Schlüssel im Android-Keystore, `KartenSitzung`). |
| Rosenweg-Konto | „Mit Rosenweg-Konto anmelden“ im Browser |

| Regel | Inhalt |
|---|---|
| je App | Jede App meldet sich selbst an; Abmelden betrifft nur diese App. |
| Sitzungen | höchstens 20 aktive Karten-Sitzungen pro Person; eine neue löst die am längsten unbenutzte ab. Laufzeit 30 Tage. |
| Karte entzogen | Die Karte wird bei jedem Erneuern live in UniFi Access geprüft; entzogen = Sitzung weg. |
| Technik | sieht und widerruft jede Sitzung einzeln. |
| Geteilte Telefone | dort gilt die Anmeldung von Rosenweg Gerät, kein eigener Schirm. |

So bindet eine App den Schirm ein:

```kotlin
override fun onResume() {
    super.onResume()
    if (RosenwegAnmeldeSchirm.zeigenFallsNoetig(this)) return
    // ab hier angemeldet: RosenwegAnmeldung.zugangsToken(this) gilt für beide Wege
}
```

`RosenwegAnmeldung.angemeldet`, `name`, `zugangsToken`, `zugangUngueltig` und `abmelden` decken beide Wege ab. Server: Rosenweg/Website, `api/routes/app-karte.js`.

## Rosenweg Updater

Auf Handys ohne Rosenweg Gerät prüft genau eine installierte Rosenweg-App den App-Katalog der Telefonanlage und aktualisiert alle installierten Rosenweg-Apps.

| Prüfung vor der Installation | muss stimmen |
|---|---|
| Grösse und SHA-256 | wie im Katalog |
| Paketname und Version | passend, neuer als installiert |
| Signatur | gleich der eigenen (Rosenweg-Schlüssel) |

Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt Rosenweg Gerät.

## Aufbau

| Teil | Inhalt |
|---|---|
| `commons/` | Fossify Commons (Rosenweg-Fassung) |
| `cloud/…/RosenwegAnmeldeSchirm.kt`, `RosenwegAnmeldung.kt`, `KartenSitzung.kt` | Anmeldeschirm, Anmeldung mit Karte oder Konto |
| `cloud/…/AnmeldenActivity.kt`, `NextcloudZugang.kt`, `LoginFlow.kt`, `Konto.kt` | Nextcloud-Zugang: App-Passwort von der Anlage oder Login-Flow im Browser |
| `cloud/…/Hochladen.kt`, `Ablage.kt`, `Einstellungen.kt`, `Hinweis.kt`, `WebDav.kt` | Warteschlange, Dateien, Einstellungen pro Person |
| `cloud/…/dav/` | CalDAV/CardDAV-Abgleich (`KalenderAbgleich`, `KontakteAbgleich`) |
| `anmeldeschirm/` | `AnmeldeOberflaeche`, Kartenleser, Logo |
| `updater/` | `RosenwegUpdater`, `UpdateActivity` |
| `samples/` | Beispiel-App aus Fossify |

## Bauen und verwenden

Lokal ins Maven-Repository veröffentlichen (`mavenLocal()`):

```bash
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal
```

Das veröffentlicht `org.fossify:commons:7.1.3-rosenweg.1` sowie `cloud`, `anmeldeschirm` und `updater` mit den Versionen aus ihren `build.gradle.kts` (überschreibbar mit `-PCLOUD_VERSION=…`, `-PANMELDESCHIRM_VERSION=…`, `-PUPDATER_VERSION=…`). Danach die Apps bauen, z. B. `./gradlew assembleFossRelease`.

Mehr: [docs/entwickeln.md](docs/entwickeln.md).

## CI und Pakete

| Workflow | Auslöser | Ergebnis |
|---|---|---|
| `pakete.yml` (Rosenweg) | Tag `v*` oder von Hand | alle Module nach GitHub Packages (`maven.pkg.github.com/Rosenweg/commons-rosenweg`); `commons` bekommt den Tag ohne `v` als Version, die anderen ihre Versionen aus `build.gradle.kts`; vorhandene Versionen werden übersprungen |
| `pr.yml`, `release.yml` u. a. | aus Fossify übernommen | Prüfungen des Originals |

Die Apps selbst stehen auf [rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps).

## Konfiguration

| Wo | Name | Wozu |
|---|---|---|
| `~/.gradle/gradle.properties` | `gpr.user`, `gpr.key` | Pakete lokal lesen; `gpr.key` = Token mit `read:packages` (z. B. `gh auth token`) |
| GitHub Actions | `GPR_TOKEN` (Org-Secret) oder `GITHUB_TOKEN` | Pakete in der CI der Apps lesen bzw. hier veröffentlichen |
| Gradle-Eigenschaften | `VERSION`, `CLOUD_VERSION`, `ANMELDESCHIRM_VERSION`, `UPDATER_VERSION` | Versionen beim Veröffentlichen |
| Server (Rosenweg/Website) | `APP_KARTE_MAX_SITZUNGEN`, `APP_KARTE_TAGE` | Karten-Sitzungen je Person (Vorgabe 20) und Laufzeit (30 Tage) |

`mavenLocal()` bleibt als Rückfall.

## Herkunft und Lizenz

Fork von [Fossify Commons](https://github.com/FossifyOrg/commons). Die Rosenweg-Anpassungen und die eigenen Module sind in `main` gemergt; `main` ist der Hauptzweig. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
