#!/usr/bin/env python3
"""Abhaengige Repos der Organisation auf eine neue Fassung heben - je Repo ein PR.

Zwei Arten:

  maven  Rosenweg-Pakete aus GitHub Packages (commons-rosenweg), z.B.
         anheben.py maven cloud=0.13.5 updater=0.1.5 commons=7.1.3-rosenweg.8
         Gesucht wird in *.gradle, *.gradle.kts ("ch.rosenweg4303:cloud:0.13.4") und
         gradle/libs.versions.toml (module = "ch.rosenweg4303:cloud", version.ref = ...).
         "commons" steht fuer org.fossify:commons (die Rosenweg-Fassung).

  pub    Flutter-Paket als Git-Abhaengigkeit mit festem Tag, z.B.
         anheben.py pub rosenweg_basis https://github.com/Rosenweg/rosenweg-basis.git v0.5.3
         Gesucht wird in pubspec.yaml (git: url + ref); danach pubspec.lock mit
         "flutter pub upgrade <paket>" neu (Flutter muss im PATH sein).

Die abhaengigen Repos werden gefunden, nicht aufgezaehlt: alle nicht archivierten Repos der
Organisation, deren Dateien die Abhaengigkeit enthalten (Git-Baum des Standardzweigs). Angehoben
wird nur, was aelter ist. Jede App bekommt eine neue Patch-Version (gradle.properties
VERSION_NAME/VERSION_CODE, sonst versionCode/versionName in app/build.gradle[.kts], sonst
"version:" in pubspec.yaml), damit ein Release entsteht - nicht im Quell-Repo selbst (dort z.B.
die App-Vorlage von rosenweg-basis) und nicht, wenn der Zweig sie schon hochgezaehlt hat.

Gibt es schon einen offenen PR, dessen Zweig die neue Fassung enthaelt (auch einen von Hand),
wird kein zweiter geoeffnet: der Zweig wird nur nachgefuehrt (z.B. pubspec.lock auf den Tag).

Braucht: git, gh (GH_TOKEN mit contents/pull-requests: write auf die Repos), Python 3.9+.
Optionen: --org Rosenweg, --quelle <repo> (nur Vorlagen, keine Versionsnummer), --nur a,b,
--ohne a,b, --trocken (nur anzeigen).
"""

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

GRUPPE = "ch.rosenweg4303"
BOT_NAME = "rosenweg-abhaengige[bot]"
BOT_MAIL = "rosenweg-abhaengige[bot]@users.noreply.github.com"
GRADLE_DATEIEN = re.compile(r"(^|/)(build\.gradle(\.kts)?|libs\.versions\.toml)$")
PUB_DATEI = re.compile(r"(^|/)pubspec\.yaml$")
# Nicht durchsuchen: Bauordner, Fremdcode, Beispiele mit path-Abhaengigkeit
AUSLASSEN = re.compile(r"(^|/)(build|\.dart_tool|node_modules|vendor|third_party|\.git)/")


def lauf(*args, cwd=None, pruefen=True, eingabe=None):
    r = subprocess.run(args, cwd=cwd, text=True, capture_output=True, input=eingabe)
    if pruefen and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def gh_json(*args):
    out = lauf("gh", *args)
    return json.loads(out) if out.strip() else None


def teile(v):
    """'7.1.3-rosenweg.8' -> (7, 1, 3, 8); 'v0.5.3' -> (0, 5, 3). Fuer den Vergleich neuer/aelter."""
    return tuple(int(x) for x in re.findall(r"\d+", v))


def neuer(neu, alt):
    try:
        return teile(neu) > teile(alt)
    except ValueError:
        return False


# ─── Ersetzen ───────────────────────────────────────────────────────────────

def maven_koordinate(modul):
    return ("org.fossify", "commons") if modul == "commons" else (GRUPPE, modul)


def maven_ersetzen(pfad, text, module):
    """-> (neuer Text, [(modul, alt, neu)])"""
    aenderungen = []
    for modul, ver in module.items():
        g, a = maven_koordinate(modul)
        if pfad.endswith(".toml"):
            # Eintrag in [libraries]: module = "g:a" (oder group/name) mit version.ref oder version
            muster = re.compile(
                r'^[^\n=]+=\s*\{[^}\n]*(?:module\s*=\s*"' + re.escape(f"{g}:{a}") + r'"|group\s*=\s*"'
                + re.escape(g) + r'"\s*,\s*name\s*=\s*"' + re.escape(a) + r'")[^}\n]*\}', re.M)
            for m in muster.finditer(text):
                zeile = m.group(0)
                ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', zeile)
                if ref:
                    vm = re.compile(r'^(\s*' + re.escape(ref.group(1)) + r'\s*=\s*")([^"]+)(")', re.M)
                    alt = vm.search(text)
                    if alt and neuer(ver, alt.group(2)):
                        aenderungen.append((modul, alt.group(2), ver))
                        text = vm.sub(lambda x: x.group(1) + ver + x.group(3), text, count=1)
                else:
                    inl = re.search(r'version\s*=\s*"([^"]+)"', zeile)
                    if inl and neuer(ver, inl.group(1)):
                        aenderungen.append((modul, inl.group(1), ver))
                        text = text.replace(zeile, zeile.replace(f'"{inl.group(1)}"', f'"{ver}"', 1), 1)
        else:
            muster = re.compile(r'(["\'])' + re.escape(f"{g}:{a}:") + r'([^"\'$]+)\1')

            def ein(m):
                if neuer(ver, m.group(2)):
                    aenderungen.append((modul, m.group(2), ver))
                    return f"{m.group(1)}{g}:{a}:{ver}{m.group(1)}"
                return m.group(0)
            text = muster.sub(ein, text)
    return text, aenderungen


def pub_ersetzen(text, paket, url, ref):
    """git-Abhaengigkeit <paket> mit <url>: ref auf den neuen Tag. -> (Text, [(paket, alt, neu)])"""
    block = re.compile(r"^(\s*)" + re.escape(paket) + r":\s*\n((?:\1\s+.*\n|\s*#.*\n|\s*\n)*)", re.M)
    aenderungen = []

    def ein(m):
        inhalt = m.group(2)
        if url.rstrip("/").removesuffix(".git") not in inhalt:
            return m.group(0)
        r = re.search(r"^(\s*ref:\s*)[\"']?([^\"'\s#]+)[\"']?", inhalt, re.M)
        if not r or not neuer(ref, r.group(2)):
            return m.group(0)
        aenderungen.append((paket, r.group(2), ref))
        neu = inhalt[:r.start()] + r.group(1) + ref + inhalt[r.end():]
        # Alte Merkzettel ("nach dem Merge auf den Tag ... umstellen") gelten nicht mehr
        neu = re.sub(r"^\s*#.*nach dem Merge auf den Tag.*\n", "", neu, flags=re.M)
        return m.group(0)[:m.start(2) - m.start(0)] + neu
    return block.sub(ein, text), aenderungen


# ─── Version der App ────────────────────────────────────────────────────────

def patch_hoch(name):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)(.*)$", name)
    if m:
        return f"{m.group(1)}.{m.group(2)}.{int(m.group(3)) + 1}{m.group(4)}"
    m = re.match(r"^(\d+)\.(\d+)$", name)
    return f"{m.group(1)}.{m.group(2)}.1" if m else None


VERSIONS_DATEIEN = ("gradle.properties", "app/build.gradle.kts", "app/build.gradle", "pubspec.yaml")


def version_lesen(ordner):
    """-> (Datei, Name, Code) der App-Version oder None."""
    gp = os.path.join(ordner, "gradle.properties")
    if os.path.exists(gp):
        t = open(gp, encoding="utf-8").read()
        n = re.search(r"^VERSION_NAME=(.+)$", t, re.M)
        c = re.search(r"^VERSION_CODE=(\d+)$", t, re.M)
        if n and c:
            return "gradle.properties", n.group(1).strip(), int(c.group(1))
    for datei in ("app/build.gradle.kts", "app/build.gradle"):
        p = os.path.join(ordner, datei)
        if os.path.exists(p):
            t = open(p, encoding="utf-8").read()
            c = re.search(r"\bversionCode\s*=?\s*(\d+)", t)
            n = re.search(r"\bversionName\s*=?\s*\"([^\"]+)\"", t)
            if n and c:
                return datei, n.group(1), int(c.group(1))
    p = os.path.join(ordner, "pubspec.yaml")
    if os.path.exists(p):
        m = re.search(r"^version:\s*([0-9][^\s+#]*)(?:\+(\d+))?", open(p, encoding="utf-8").read(), re.M)
        if m:
            return "pubspec.yaml", m.group(1), int(m.group(2)) if m.group(2) else None
    return None


def version_hoch(ordner):
    v = version_lesen(ordner)
    if not v:
        return None
    datei, name, code = v
    neu = patch_hoch(name)
    if not neu:
        return None
    p = os.path.join(ordner, datei)
    t = open(p, encoding="utf-8").read()
    if datei == "gradle.properties":
        t = re.sub(r"^VERSION_NAME=.*$", f"VERSION_NAME={neu}", t, count=1, flags=re.M)
        t = re.sub(r"^VERSION_CODE=\d+$", f"VERSION_CODE={code + 1}", t, count=1, flags=re.M)
    elif datei == "pubspec.yaml":
        rest = f"+{code + 1}" if code is not None else ""
        t = re.sub(r"^version:\s*\S+", f"version: {neu}{rest}", t, count=1, flags=re.M)
    else:
        t = re.sub(r"(\bversionCode\s*=?\s*)\d+", lambda m: f"{m.group(1)}{code + 1}", t, count=1)
        t = re.sub(r"(\bversionName\s*=?\s*\")[^\"]+", lambda m: f"{m.group(1)}{neu}", t, count=1)
    open(p, "w", encoding="utf-8").write(t)
    return f"{name} ({code}) → {neu}" + (f" ({code + 1})" if code is not None else "")


# ─── Repos finden ───────────────────────────────────────────────────────────

def dateien_mit(org, repo, zweig, muster, nadel):
    """Pfade im Standardzweig, die zum Muster passen und die Nadel enthalten."""
    try:
        baum = gh_json("api", f"repos/{org}/{repo}/git/trees/{zweig}?recursive=1")
    except RuntimeError:
        return []
    treffer = []
    for e in (baum or {}).get("tree", []):
        p = e.get("path", "")
        if e.get("type") != "blob" or not muster.search(p) or AUSLASSEN.search(p):
            continue
        try:
            inhalt = gh_json("api", f"repos/{org}/{repo}/contents/{p}?ref={zweig}")
            text = base64.b64decode(inhalt["content"]).decode("utf-8", "replace")
        except (RuntimeError, KeyError, TypeError):
            continue
        if any(n in text for n in nadel):
            treffer.append(p)
    return treffer


# ─── Je Repo ────────────────────────────────────────────────────────────────

def bearbeiten(org, repo, zweig, pfade, art, args, kurz, titel, quelle, trocken):
    tmp = tempfile.mkdtemp(prefix=f"abh-{repo}-")
    try:
        ordner = os.path.join(tmp, repo)
        lauf("gh", "repo", "clone", f"{org}/{repo}", ordner, "--", "--quiet", "--depth", "50",
             "--no-single-branch")
        # Offener PR, dessen Zweig die neue Fassung schon hat? Dann diesen nachfuehren.
        prs = gh_json("pr", "list", "-R", f"{org}/{repo}", "--state", "open", "--json",
                      "number,headRefName,url,isCrossRepository") or []
        ziel = f"abhaengig/{kurz}"
        vorhanden = None
        for pr in prs:
            if pr["isCrossRepository"]:
                continue
            if pr["headRefName"] == ziel:
                vorhanden = pr
                break
        if not vorhanden:
            for pr in prs:
                if pr["isCrossRepository"]:
                    continue
                lauf("git", "fetch", "--quiet", "--depth", "50", "origin", pr["headRefName"], cwd=ordner, pruefen=False)
                for p in pfade:
                    t = lauf("git", "show", f"FETCH_HEAD:{p}", cwd=ordner, pruefen=False)
                    if t and not _aenderungen(art, p, t, args)[1] and _hat_ziel(art, p, t, args):
                        vorhanden = pr
                        break
                if vorhanden:
                    break
        if vorhanden:
            lauf("git", "fetch", "--quiet", "origin", vorhanden["headRefName"], cwd=ordner)
            lauf("git", "checkout", "--quiet", "-B", vorhanden["headRefName"], "FETCH_HEAD", cwd=ordner)
            zweig_arbeit = vorhanden["headRefName"]
        else:
            lauf("git", "checkout", "--quiet", "-B", ziel, f"origin/{zweig}", cwd=ordner)
            zweig_arbeit = ziel

        alles = []
        for p in pfade:
            dp = os.path.join(ordner, p)
            if not os.path.exists(dp):
                continue
            t = open(dp, encoding="utf-8").read()
            neu, aend = _aenderungen(art, p, t, args)
            if aend:
                open(dp, "w", encoding="utf-8").write(neu)
                alles += [(p,) + a for a in aend]

        lock = []
        if art == "pub":
            for p in pfade:
                d = os.path.dirname(os.path.join(ordner, p))
                if os.path.exists(os.path.join(d, "pubspec.lock")) and shutil.which("flutter"):
                    r = subprocess.run(["flutter", "pub", "upgrade", args.paket], cwd=d, text=True,
                                       capture_output=True)
                    if r.returncode != 0:
                        print(f"::warning::{repo}/{p}: flutter pub upgrade: {r.stderr.strip()[-400:]}")
                    else:
                        lock.append(os.path.relpath(os.path.join(d, "pubspec.lock"), ordner))

        version = None
        if repo != quelle and (alles or vorhanden):
            # Patch-Version nur, wenn der Zweig sie gegenueber dem Standardzweig noch nicht hat
            haupt = tempfile.mkdtemp(dir=tmp)
            for datei in VERSIONS_DATEIEN:
                inhalt = lauf("git", "show", f"origin/{zweig}:{datei}", cwd=ordner, pruefen=False)
                if inhalt:
                    os.makedirs(os.path.dirname(os.path.join(haupt, datei)), exist_ok=True)
                    open(os.path.join(haupt, datei), "w", encoding="utf-8").write(inhalt)
            if version_lesen(haupt) == version_lesen(ordner):
                version = version_hoch(ordner)

        if not lauf("git", "status", "--porcelain", cwd=ordner).strip():
            print(f"{repo}: nichts zu tun" + (f" (PR {vorhanden['url']} ist aktuell)" if vorhanden else ""))
            return
        print(f"{repo}: {', '.join(f'{p}: {m} {a} → {n}' for p, m, a, n in alles) or 'Lock nachgefuehrt'}"
              + (f"; Version {version}" if version else ""))
        if trocken:
            print(lauf("git", "diff", cwd=ordner))
            return

        if alles:
            titel = ", ".join(dict.fromkeys(f"{m} {n}" for _, m, _, n in alles))
        lauf("git", "-c", f"user.name={BOT_NAME}", "-c", f"user.email={BOT_MAIL}", "commit", "--quiet", "-a",
             "-m", titel + (f"; Version {version.split(' → ')[1]}" if version else "") + "\n\n"
             + "Automatisch: Workflow \"Abhaengige anheben\" (" + os.environ.get("ABH_LAUF", "von Hand") + ").",
             cwd=ordner)
        lauf("git", "push", "--quiet", "origin", f"HEAD:{zweig_arbeit}", cwd=ordner)
        if vorhanden:
            print(f"{repo}: Zweig {zweig_arbeit} nachgefuehrt ({vorhanden['url']})")
            return
        zeilen = [f"- `{p}`: {m} **{a} → {n}**" for p, m, a, n in alles]
        if lock:
            zeilen.append(f"- `{', '.join(lock)}`: `flutter pub upgrade {args.paket}`")
        if version:
            zeilen.append(f"- App-Version {version} (damit ein Release entsteht)")
        body = (f"Neue Fassung von {quelle}: **{titel}**.\n\n" + "\n".join(zeilen)
                + "\n\nÄnderungen der Fassung: siehe CHANGELOG/Commits in "
                + f"[{org}/{quelle}](https://github.com/{org}/{quelle}).\n\n"
                + "Geöffnet vom Workflow „Abhängige anheben“ (" + os.environ.get("ABH_LAUF", "von Hand")
                + "). Die Fassung steht fest im Repo; mergen, wenn die CI grün ist, danach wie gewohnt "
                + "taggen und veröffentlichen.\n")
        url = lauf("gh", "pr", "create", "-R", f"{org}/{repo}", "--base", zweig, "--head", zweig_arbeit,
                   "--title", titel, "--body", body).strip()
        print(f"{repo}: {url}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _aenderungen(art, pfad, text, args):
    if art == "maven":
        return maven_ersetzen(pfad, text, args.module)
    return pub_ersetzen(text, args.paket, args.url, args.ref)


def _hat_ziel(art, pfad, text, args):
    """Steht die Abhaengigkeit in dieser Datei schon genau auf der neuen Fassung?"""
    if art == "maven":
        for m, v in args.module.items():
            _, aend = maven_ersetzen(pfad, text, {m: "999999"})
            if any(alt == v for _, alt, _ in aend):
                return True
        return False
    _, aend = pub_ersetzen(text, args.paket, args.url, "v999999")
    return any(alt == args.ref for _, alt, _ in aend)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--org", default=os.environ.get("ABH_ORG", "Rosenweg"))
    ap.add_argument("--quelle", default="", help="Repo, aus dem die Fassung kommt (keine Versionsnummer dort)")
    ap.add_argument("--nur", default="", help="nur diese Repos (Komma)")
    ap.add_argument("--ohne", default="", help="diese Repos auslassen (Komma)")
    ap.add_argument("--trocken", action="store_true")
    sub = ap.add_subparsers(dest="art", required=True)
    m = sub.add_parser("maven")
    m.add_argument("module", nargs="+", help="modul=version, z.B. cloud=0.13.5")
    p = sub.add_parser("pub")
    p.add_argument("paket")
    p.add_argument("url")
    p.add_argument("ref")
    args = ap.parse_args()

    if args.art == "maven":
        args.module = dict(x.split("=", 1) for x in args.module if "=" in x and x.split("=", 1)[1])
        if not args.module:
            print("Keine Module angegeben - nichts zu tun.")
            return
        nadel = [":".join(maven_koordinate(mm)) for mm in args.module]
        muster = GRADLE_DATEIEN
        titel = ", ".join(f"{mm} {v}" for mm, v in args.module.items())
        kurz = "-".join(f"{mm}-{v}" for mm, v in args.module.items())
    else:
        nadel = [args.url.rstrip("/").removesuffix(".git")]
        muster = PUB_DATEI
        titel = f"{args.paket} {args.ref}"
        kurz = f"{args.paket}-{args.ref}"

    nur = {x for x in args.nur.split(",") if x}
    ohne = {x for x in args.ohne.split(",") if x}
    repos = gh_json("repo", "list", args.org, "--no-archived", "--limit", "500", "--json",
                    "name,defaultBranchRef,isFork") or []
    fehler = 0
    for r in sorted(repos, key=lambda x: x["name"].lower()):
        name = r["name"]
        if (nur and name not in nur) or name in ohne or not r.get("defaultBranchRef"):
            continue
        zweig = r["defaultBranchRef"]["name"]
        pfade = dateien_mit(args.org, name, zweig, muster, nadel)
        if not pfade:
            continue
        try:
            bearbeiten(args.org, name, zweig, pfade, args.art, args, kurz, titel, args.quelle, args.trocken)
        except Exception as e:  # ein Repo darf die anderen nicht aufhalten
            fehler += 1
            print(f"::error::{name}: {e}")
    sys.exit(1 if fehler else 0)


if __name__ == "__main__":
    main()
