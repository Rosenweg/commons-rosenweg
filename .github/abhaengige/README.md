# Abhängige anheben

Neue Fassungen der Rosenweg-Bausteine kommen automatisch als PR in jedes Repo, das sie nutzt. Die Fassungen bleiben fest eingetragen; gemergt wird von Hand, wenn die CI grün ist.

| Quelle | Auslöser | Was angehoben wird |
|---|---|---|
| commons-rosenweg | „Pakete (GitHub Packages)“ erfolgreich (Tag `v*`) → `abhaengige-anheben.yml` | `ch.rosenweg4303:cloud`, `:updater`, `:anmeldeschirm` (wenn ihre Version in `<modul>/build.gradle.kts` gegenüber dem vorigen Tag gestiegen ist), `org.fossify:commons` (wenn sich `commons/` geändert hat) in `build.gradle[.kts]` und `gradle/libs.versions.toml` |
| rosenweg-basis | Tag `v*` → dort `.github/workflows/abhaengige.yml` | `rosenweg_basis` als Git-Abhängigkeit (`ref:`) in `pubspec.yaml`, dazu `pubspec.lock` (`flutter pub upgrade rosenweg_basis`); in rosenweg-basis selbst die App-Vorlage |

Beide rufen `.github/workflows/abhaengige.yml` (wiederverwendbar) mit dem Skript `anheben.py` auf.

- **Welche Repos:** alle nicht archivierten Repos der Organisation, in deren Standardzweig eine passende Datei die Abhängigkeit enthält. Neue Apps sind damit von selbst dabei; nichts fest verdrahtet.
- **Je Repo ein PR** „cloud 0.13.5, updater 0.1.5“ bzw. „rosenweg_basis v0.5.3“ auf dem Zweig `abhaengig/…`, mit neuer Patch-Version der App (`gradle.properties` VERSION_NAME/VERSION_CODE, sonst `app/build.gradle[.kts]`, sonst `pubspec.yaml` `x.y.z+n`), damit ein Release entsteht. Im Quell-Repo selbst keine Versionsnummer.
- **Kein Doppel:** Hat ein offener PR (auch von Hand) die neue Fassung schon, wird nur dessen Zweig nachgeführt (z.B. `pubspec.lock` auf den Tag-Commit), kein zweiter PR. Angehoben wird nur, was älter ist.
- **Workflows** der Ziel-Repos werden nie geändert.

Von Hand (auch zum Ausprobieren):

```bash
gh workflow run abhaengige-anheben.yml -R Rosenweg/commons-rosenweg -f module="cloud=0.13.4 updater=0.1.4" -f trocken=true
gh workflow run abhaengige.yml -R Rosenweg/rosenweg-basis -f tag=v0.5.2 -f trocken=true
# lokal (gh angemeldet):
python3 .github/abhaengige/anheben.py --trocken --nur calendar-rosenweg maven updater=0.1.4
```

## Zugang einrichten (einmalig)

`GITHUB_TOKEN` darf nur ins eigene Repo schreiben, und PRs damit starten keine CI. Darum:

**Bevorzugt: GitHub-App** (Organisation Rosenweg › Settings › Developer settings › GitHub Apps › New GitHub App)

| Feld | Wert |
|---|---|
| Name | `Rosenweg Abhaengige` |
| Homepage | `https://github.com/Rosenweg/commons-rosenweg` |
| Webhook | aus (Haken „Active“ weg) |
| Repository permissions | **Contents: Read and write**, **Pull requests: Read and write**, Metadata: Read-only (automatisch). Sonst nichts – insbesondere nicht *Workflows*. |
| Where can this app be installed | Only on this account |

Danach: *Generate a private key* (PEM-Datei), *Install App* › Rosenweg › **All repositories** (neue Repos sind dann von selbst dabei; sonst jedes Repo einzeln auswählen). Dann in der Organisation (Settings › Secrets and variables › Actions):

| Art | Name | Inhalt | Repository access |
|---|---|---|---|
| Variable | `ABHAENGIGE_CLIENT_ID` | Client ID der App (Seite der App, „Client ID“, beginnt mit `Iv`) | commons-rosenweg, rosenweg-basis |
| Secret | `ABHAENGIGE_APP_KEY` | ganzer Inhalt der PEM-Datei | commons-rosenweg, rosenweg-basis |

**Sonst: fein abgestuftes PAT** (Settings › Developer settings › Fine-grained tokens): Resource owner *Rosenweg*, Repository access *All repositories*, Permissions *Contents: Read and write*, *Pull requests: Read and write*; Ablauf höchstens 1 Jahr. Als Org-Secret **`ABHAENGIGE_TOKEN`** für commons-rosenweg und rosenweg-basis. Die PRs erscheinen dann unter dem Namen der Person, der das Token gehört. Ist die App eingerichtet, wird das PAT nicht gebraucht.

Runner: GitHub-Runner (`ubuntu-latest`); beide Quell-Repos sind öffentlich bzw. werden es. Für ein privates Quell-Repo `runner: '["self-hosted","rosenweg"]'` übergeben.
