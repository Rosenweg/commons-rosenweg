package ch.rosenweg4303.updater

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import java.io.File
import java.lang.ref.WeakReference

/**
 * Seite "Rosenweg-Updates": "Nach Updates suchen" ([RosenwegUpdater.jetztPruefen]) und Ziel der
 * Meldungen. Prueft sofort und zeigt je App eine Zeile ("Aktuell", "Neue Fassung X -
 * Installieren", Fehler im Klartext). Laedt und prueft eine APK nach der anderen und uebergibt
 * sie dem Android-Installer; Android fragt pro App nach. Aussehen: [UpdateAnsicht].
 *
 * 0.1.8 (Stefan 27.11.2026):
 * - Recht "Apps aus dieser Quelle installieren" VOR dem Installieren pruefen: Hinweis mit
 *   "Erlauben", beim Zurueckkehren erneut pruefen und automatisch weitermachen.
 * - Wartet eine Bestaetigung, steht eine Benachrichtigung da, die direkt zu ihr fuehrt.
 */
class UpdateActivity : Activity() {
    private lateinit var a: UpdateAnsicht
    private var updates: List<RosenwegUpdater.Update> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        a = UpdateAnsicht(this)
        setContentView(a.wurzel)
        if (laeuft) zeigeLaufend() else laden()
        fortsetzenAusMeldung(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        fortsetzenAusMeldung(i)
    }

    /** Aus der Benachrichtigung "wartet auf Bestaetigung": die offene Bestaetigung gleich zeigen. */
    private fun fortsetzenAusMeldung(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_FORTSETZEN, false) != true) return
        i.removeExtra(EXTRA_FORTSETZEN)
        offeneBestaetigung?.let { bestaetigungZeigen(this, it) }
    }

    override fun onResume() {
        super.onResume()
        when {
            // zurueck aus "Apps aus dieser Quelle installieren": erneut pruefen, dann weiter
            wartetAufRecht && darfInstallieren() -> {
                wartetAufRecht = false; a.hinweis(null)
                // nach "Installieren" erlaubt: gleich weiter; sonst nur den Knopf zuruecksetzen
                if (warteschlange.isNotEmpty() && !laeuft) naechste()
                else if (!laeuft && updates.isNotEmpty()) a.hauptknopf(if (updates.size == 1) "Installieren" else "Alle installieren") { starten() }
            }
            wartetAufRecht -> rechtFehlt()
            laeuft -> zeigeLaufend()
        }
    }

    private fun darfInstallieren(): Boolean {
        val ja = packageManager.canRequestPackageInstalls()
        android.util.Log.i(RosenwegUpdater.TAG, "Recht Unbekannte Apps installieren: $ja")
        return ja
    }

    /** Ohne das Recht nicht ins Leere laufen: klarer Hinweis mit "Erlauben". */
    private fun rechtFehlt() {
        a.hinweis("Damit Rosenweg-Apps Updates installieren dürfen, einmal „Apps aus dieser Quelle zulassen“ " +
            "einschalten und dann zurück tippen – es geht danach von selbst weiter.", "Erlauben") { erlauben() }
        a.hauptknopf("Erlauben") { erlauben() }
    }

    private fun erlauben() {
        wartetAufRecht = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            a.hinweis("Die Einstellung liess sich nicht öffnen. Bitte unter Einstellungen › Apps › " +
                "${applicationInfo.loadLabel(packageManager)} › „Unbekannte Apps installieren“ erlauben.")
        }
    }

    /** Eine Installation laeuft schon (auch aus einer frueheren Instanz dieser Seite). */
    private fun zeigeLaufend() {
        val u = aktuell ?: return
        if (a.zeilen.isEmpty()) {
            updates = (listOf(u) + warteschlange).distinctBy { it.paket }
            a.zeilenSetzen(updates.map { zeile(it) })
        }
        a.kopf("Installiert …", u.name)
        val offen = offeneBestaetigung
        if (offen != null) {
            a.zeilen[u.paket]?.setzen(UpdateAnsicht.Zustand.BESTAETIGEN)
            a.hauptknopf("Installation fortsetzen") { bestaetigungZeigen(this, offen) }
        } else {
            a.zeilen[u.paket]?.setzen(UpdateAnsicht.Zustand.LAEDT)
            a.hauptknopf("Bitte warten …", aktiv = false)
        }
    }

    /** Prüfen und das Ergebnis zeigen. */
    private fun laden() {
        a.kopf("Suche Updates …", eigeneFassung())
        a.zeilenSetzen(emptyList()); a.hinweis(null); a.nebenknopf(null)
        a.hauptknopf("Bitte warten …", aktiv = false)
        if (RosenwegUpdater.geraetDa(this)) {
            a.kopf("Alles im Griff", "Auf diesem Telefon hält Rosenweg Gerät alle Rosenweg-Apps aktuell.")
            a.hauptknopf("Schliessen") { finish() }
            Thread { RosenwegUpdater.standMelden(this, null) }.start()
            return
        }
        // Recht gleich beim Oeffnen pruefen (Befund A37 0.1.8: die Karte fehlte)
        if (!darfInstallieren()) rechtFehlt()
        a.sucht(true)
        Thread {
            val ergebnis = try { Result.success(RosenwegUpdater.verfuegbar(this)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread { zeigen(ergebnis) }
        }.start()
    }

    private fun zeile(u: RosenwegUpdater.Update) = a.Zeile(u.paket, u.name, fassung(u.paket), u.versionName, symbol(u.paket))

    private fun zeigen(ergebnis: Result<List<RosenwegUpdater.Update>>) {
        if (isFinishing) return
        a.sucht(false)
        val l = ergebnis.getOrElse { e ->
            a.kopf("Keine Verbindung", eigeneFassung())
            a.hinweis(RosenwegUpdater.klartext(e))
            a.hauptknopf("Nochmals versuchen") { laden() }
            return
        }
        updates = l
        a.zeilenSetzen(l.map { zeile(it) })
        if (l.isEmpty()) {
            a.kopf("Alles aktuell", "Alle Rosenweg-Apps auf diesem Handy sind auf dem neuesten Stand.")
            RosenwegUpdater.abmelden(this)
            a.zeilenSetzen(listOf(a.Zeile(packageName, applicationInfo.loadLabel(packageManager).toString(), "",
                fassung(packageName), symbol(packageName)).also { it.setzen(UpdateAnsicht.Zustand.AKTUELL) }))
            a.hauptknopf("Schliessen") { finish() }
            return
        }
        a.kopf(if (l.size == 1) "Neue Fassung ${l[0].versionName}" else "${l.size} neue Fassungen",
            if (l.size == 1) l[0].name else "für Rosenweg-Apps auf diesem Handy")
        a.hauptknopf(if (l.size == 1) "Installieren" else "Alle installieren") { starten() }
        if (!darfInstallieren()) rechtFehlt()
    }

    private fun fassung(paket: String): String = RosenwegUpdater.info(this, paket)?.versionName ?: ""

    private fun symbol(paket: String) = try { packageManager.getApplicationIcon(paket) } catch (e: Exception) { null }

    private fun eigeneFassung() = "${applicationInfo.loadLabel(packageManager)} ${fassung(packageName)}"

    private fun starten() {
        if (laeuft) { zeigeLaufend(); return }   // nie zwei Installationen gleichzeitig (Befund A37)
        // die eigene App zuletzt: ihr Update beendet diesen Prozess
        warteschlange.clear(); warteschlange.addAll(updates.sortedBy { it.paket == packageName })
        if (!darfInstallieren()) { rechtFehlt(); erlauben(); return }
        naechste()
    }

    private fun naechste() {
        val u = warteschlange.firstOrNull()
        if (u == null) {
            aktuell = null
            a.kopf("Fertig", "Alle Updates sind installiert.")
            a.hauptknopf("Nochmals suchen") { laden() }
            a.nebenknopf("Schliessen") { finish() }
            return
        }
        aktuell = u; aktuelleSession = -1; offeneBestaetigung = null
        if (a.zeilen[u.paket] == null) a.zeilenSetzen(updates.map { zeile(it) })
        a.kopf("Installiert …", u.name)
        a.hauptknopf("Bitte warten …", aktiv = false); a.nebenknopf(null)
        val z = a.zeilen[u.paket]
        z?.setzen(UpdateAnsicht.Zustand.LAEDT, anteil = 0f)
        Thread {
            try {
                val f = RosenwegUpdater.laden(this, u) { x -> runOnUiThread { z?.setzen(UpdateAnsicht.Zustand.LAEDT, anteil = x) } }
                runOnUiThread { z?.setzen(UpdateAnsicht.Zustand.BESTAETIGEN) }
                installieren(f, u)
            } catch (e: Exception) {
                runOnUiThread {
                    z?.setzen(UpdateAnsicht.Zustand.FEHLER, RosenwegUpdater.klartext(e))
                    warteschlange.remove(u); aktuell = null
                    a.hauptknopf(if (warteschlange.isEmpty()) "Nochmals suchen" else "Weiter installieren") {
                        if (warteschlange.isEmpty()) laden() else naechste()
                    }
                }
            }
        }.start()
    }

    private fun installieren(f: File, u: RosenwegUpdater.Update) {
        val pi = packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(u.paket)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(p)
        aktuelleSession = id
        try {
            pi.openSession(id).use { s ->
                f.inputStream().use { inp -> s.openWrite("apk", 0, f.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                val rueck = PendingIntent.getBroadcast(this, id, Intent(this, InstallStatus::class.java).putExtra("paket", u.paket),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(rueck.intentSender)
            }
        } finally {
            f.delete()
        }
    }

    /** Von InstallStatus: die laufende Installation ist fertig (oder abgebrochen). */
    internal fun fertig(u: RosenwegUpdater.Update, ok: Boolean, meldung: String?) {
        val z = a.zeilen[u.paket]
        if (ok) {
            z?.setzen(UpdateAnsicht.Zustand.INSTALLIERT)
            naechste()
        } else {
            // nicht still weiterspringen: Hinweis, "Nochmals versuchen" (diese App) oder weiter
            z?.setzen(UpdateAnsicht.Zustand.FEHLER, if (meldung == InstallStatus.PLAY_PROTECT) "Von Play Protect verhindert" else meldung)
            a.kopf(if (meldung == InstallStatus.PLAY_PROTECT) "Play Protect" else "Nicht installiert", u.name)
            a.hinweis("${u.name}: ${meldung ?: "nicht installiert"}")
            a.hauptknopf("Nochmals versuchen") { a.hinweis(null); warteschlange.add(0, u); naechste() }
            a.nebenknopf(if (warteschlange.isEmpty()) "Schliessen" else "Überspringen, weiter installieren") {
                a.hinweis(null); if (warteschlange.isEmpty()) finish() else naechste()
            }
        }
    }

    override fun onStart() { super.onStart(); aktiv = this }
    override fun onDestroy() { if (aktiv === this) aktiv = null; super.onDestroy() }

    companion object {
        const val EXTRA_FORTSETZEN = "ch.rosenweg4303.updater.FORTSETZEN"

        internal val warteschlange = mutableListOf<RosenwegUpdater.Update>()

        /** Die App, die gerade installiert wird, und ihre Session (0.1.7: Ergebnisse anderer
         * Sessions zaehlen nicht - vorher verschob ein doppeltes Telefon-Ergebnis die
         * Warteschlange und die Bestaetigung fuer Kontakte ging verloren, Test A37 27.11.2026). */
        internal var aktuell: RosenwegUpdater.Update? = null
        @Volatile internal var aktuelleSession = -1

        /** Bestaetigung des Android-Installers, die noch offen ist (fuer "Installation fortsetzen"). */
        internal var offeneBestaetigung: Intent? = null
        internal val laeuft: Boolean get() = aktuell != null

        /** "Erlauben" gedrueckt, zurueck aus den Einstellungen geht es weiter. */
        internal var wartetAufRecht = false

        internal fun bestaetigungZeigen(c: Context, i: Intent) {
            try {
                c.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                android.util.Log.w(RosenwegUpdater.TAG, "Bestaetigung: ${e.javaClass.simpleName}")
            }
        }

        /** Von InstallStatus: Bestaetigung offen - zeigen und als Benachrichtigung merken. */
        internal fun bestaetigungOffen(c: Context, i: Intent) {
            offeneBestaetigung = i
            RosenwegUpdater.bestaetigungMelden(c, aktuell?.name ?: "Rosenweg-App")
            aktiv?.zeigeLaufendExtern()
            bestaetigungZeigen(c, i)
        }

        /** Von InstallStatus. Nur die laufende Session zaehlt. */
        internal fun ergebnis(c: Context, session: Int, ok: Boolean, meldung: String?) {
            val u = aktuell
            if (u == null || session != aktuelleSession) {
                android.util.Log.i(RosenwegUpdater.TAG, "Ergebnis fuer fremde Session $session ignoriert")
                return
            }
            warteschlange.remove(u)
            aktuell = null; aktuelleSession = -1; offeneBestaetigung = null
            RosenwegUpdater.bestaetigungAbmelden(c)
            val a = aktiv
            if (a != null && !a.isFinishing) a.fertig(u, ok, meldung)
            // ohne sichtbare Seite: beim naechsten Oeffnen (onResume) geht es weiter
        }

        // Nur schwach gehalten (InstallStatus meldet an die sichtbare Activity), damit keine
        // beendete Activity im Speicher haengen bleibt.
        private var aktivRef: WeakReference<UpdateActivity>? = null
        internal var aktiv: UpdateActivity?
            get() = aktivRef?.get()
            set(v) { aktivRef = v?.let { WeakReference(it) } }
    }

    internal fun zeigeLaufendExtern() { if (!isFinishing) zeigeLaufend() }
}
