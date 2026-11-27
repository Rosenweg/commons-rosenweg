package ch.rosenweg4303.updater

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference

/**
 * "Nach Updates suchen" ([RosenwegUpdater.jetztPruefen]) und Ziel der Meldung: prüft sofort und
 * zeigt "Aktuell", "Neue Fassung X - Installieren" oder den Fehler im Klartext. Laedt und prueft eine APK nach der anderen und
 * uebergibt sie dem Android-Installer; Android fragt pro App nach. Die naechste kommt, wenn
 * InstallStatus das Ergebnis der vorigen meldet.
 */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var liste: TextView
    private lateinit var knopf: Button
    private var updates: List<RosenwegUpdater.Update> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Rosenweg-Updates"
        val rand = dp(24)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        liste = TextView(this).apply { setPadding(0, dp(12), 0, dp(24)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        knopf = Button(this).apply { text = "Installieren"; isEnabled = false; setOnClickListener { starten() } }
        // 0.1.7: Status fest oben, nur Liste und Knopf scrollen (vorher scrollte die Ueberschrift
        // "7 Rosenweg-Apps haben eine neue Fassung" aus dem Bild, Test A37 27.11.2026)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(rand, rand, rand, 0)
            addView(status)
            addView(ScrollView(context).apply {
                isFocusable = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, rand)
                    addView(liste); addView(knopf)
                })
            })
        })
        if (laeuft) zeigeLaufend() else laden()
    }

    override fun onResume() {
        super.onResume()
        when {
            // zurueck aus "Unbekannte Apps installieren": jetzt anfangen
            !laeuft && warteschlange.isNotEmpty() && packageManager.canRequestPackageInstalls() -> naechste()
            // eine Installation wartet auf Bestaetigung (Dialog weggewischt, Seite neu geoeffnet)
            laeuft -> zeigeLaufend()
        }
    }

    /** Eine Installation laeuft schon (auch aus einer frueheren Instanz dieser Seite). */
    private fun zeigeLaufend() {
        val u = aktuell ?: return
        status.text = "${u.name}: installieren …"
        liste.text = warteschlange.joinToString("\n") { "• ${it.name}  ${it.versionName}" }
        val offen = offeneBestaetigung
        if (offen != null) {
            knopf.text = "Installation fortsetzen"; knopf.isEnabled = true
            knopf.setOnClickListener { bestaetigungZeigen(this, offen) }
        } else {
            knopf.text = "Bitte warten …"; knopf.isEnabled = false
        }
    }

    /** Prüfen und das Ergebnis zeigen (auch für "Nach Updates suchen" aus den Einstellungen). */
    private fun laden() {
        status.text = "Suche Updates …"
        liste.text = eigeneFassung()
        knopf.isEnabled = false
        knopf.text = "Bitte warten …"
        knopf.setOnClickListener { starten() }
        if (RosenwegUpdater.geraetDa(this)) {
            status.text = "Auf diesem Telefon hält Rosenweg Gerät alle Rosenweg-Apps aktuell."
            knopf.text = "Schliessen"; knopf.isEnabled = true; knopf.setOnClickListener { finish() }
            Thread { RosenwegUpdater.standMelden(this, null) }.start()
            return
        }
        Thread {
            val ergebnis = try { Result.success(RosenwegUpdater.verfuegbar(this)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread { zeigen(ergebnis) }
        }.start()
    }

    private fun zeigen(ergebnis: Result<List<RosenwegUpdater.Update>>) {
        if (isFinishing) return
        val l = ergebnis.getOrElse { e ->
            status.text = RosenwegUpdater.klartext(e)
            knopf.text = "Nochmals versuchen"; knopf.isEnabled = true; knopf.setOnClickListener { laden() }
            return
        }
        updates = l
        knopf.setOnClickListener { starten() }
        when (l.size) {
            0 -> {
                status.text = "Aktuell: alle Rosenweg-Apps auf diesem Handy sind auf dem neuesten Stand."
                RosenwegUpdater.abmelden(this)
                liste.text = eigeneFassung()
                knopf.text = "Schliessen"; knopf.isEnabled = true; knopf.setOnClickListener { finish() }
            }
            1 -> {
                val u = l[0]
                status.text = "Neue Fassung ${u.versionName} für ${u.name}"
                liste.text = "Installiert ist ${fassung(u.paket)}."
                knopf.text = "Installieren"; knopf.isEnabled = true
            }
            else -> {
                status.text = "${l.size} Rosenweg-Apps haben eine neue Fassung"
                liste.text = l.joinToString("\n") { "• ${it.name}  ${fassung(it.paket)} → ${it.versionName}" }
                knopf.text = "Alle installieren"; knopf.isEnabled = true
            }
        }
    }

    private fun fassung(paket: String): String =
        RosenwegUpdater.info(this, paket)?.versionName ?: "?"

    private fun eigeneFassung(): String {
        val name = applicationInfo.loadLabel(packageManager)
        return "$name ${fassung(packageName)}"
    }

    private fun starten() {
        if (laeuft) { zeigeLaufend(); return }   // nie zwei Installationen gleichzeitig (Befund A37)
        // die eigene App zuletzt: ihr Update beendet diesen Prozess
        warteschlange.clear(); warteschlange.addAll(updates.sortedBy { it.paket == packageName })
        knopf.isEnabled = false
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "Einmal erlauben: „Apps aus dieser Quelle installieren“, dann zurück."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        naechste()
    }

    private fun naechste() {
        val u = warteschlange.firstOrNull()
        if (u == null) { aktuell = null; laden(); return }
        aktuell = u; aktuelleSession = -1; offeneBestaetigung = null
        status.text = "${u.name}: lade …"
        knopf.text = "Bitte warten …"; knopf.isEnabled = false
        Thread {
            try {
                val f = RosenwegUpdater.laden(this, u)
                runOnUiThread { status.text = "${u.name}: installieren …" }
                installieren(f, u)
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "${u.name}: ${RosenwegUpdater.klartext(e)}"
                    warteschlange.remove(u); aktuell = null
                    knopf.text = "Nochmals versuchen"; knopf.isEnabled = true; knopf.setOnClickListener { laden() }
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
        status.text = if (ok) "${u.name}: aktualisiert" else "${u.name}: ${meldung ?: "nicht installiert"}"
        if (ok) naechste() else {
            // abgebrochen: nicht einfach weitermachen, die Person entscheidet
            knopf.text = if (warteschlange.isEmpty()) "Nochmals suchen" else "Weiter installieren"
            knopf.isEnabled = true
            knopf.setOnClickListener { if (warteschlange.isEmpty()) laden() else naechste() }
        }
    }

    override fun onStart() { super.onStart(); aktiv = this }
    override fun onDestroy() { if (aktiv === this) aktiv = null; super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        internal val warteschlange = mutableListOf<RosenwegUpdater.Update>()

        /** Die App, die gerade installiert wird, und ihre Session (0.1.7: Ergebnisse anderer
         * Sessions zaehlen nicht - vorher verschob ein doppeltes Telefon-Ergebnis die
         * Warteschlange und die Bestaetigung fuer Kontakte ging verloren, Test A37 27.11.2026). */
        internal var aktuell: RosenwegUpdater.Update? = null
        @Volatile internal var aktuelleSession = -1

        /** Bestaetigung des Android-Installers, die noch offen ist (fuer "Installation fortsetzen"). */
        internal var offeneBestaetigung: Intent? = null
        internal val laeuft: Boolean get() = aktuell != null

        internal fun bestaetigungZeigen(c: android.content.Context, i: Intent) {
            try {
                c.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                android.util.Log.w(RosenwegUpdater.TAG, "Bestaetigung: ${e.javaClass.simpleName}")
            }
        }

        /** Von InstallStatus. Nur die laufende Session zaehlt. */
        internal fun ergebnis(c: android.content.Context, session: Int, ok: Boolean, meldung: String?) {
            val u = aktuell
            if (u == null || session != aktuelleSession) {
                android.util.Log.i(RosenwegUpdater.TAG, "Ergebnis fuer fremde Session $session ignoriert")
                return
            }
            warteschlange.remove(u)
            aktuell = null; aktuelleSession = -1; offeneBestaetigung = null
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
}
