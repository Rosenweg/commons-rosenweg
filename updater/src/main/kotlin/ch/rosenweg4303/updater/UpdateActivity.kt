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
 * Liste der Updates mit "Alle aktualisieren". Laedt und prueft eine APK nach der anderen und
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
        knopf = Button(this).apply { text = "Alle aktualisieren"; isEnabled = false; setOnClickListener { starten() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(rand, rand, rand, rand)
                addView(status); addView(liste); addView(knopf)
            })
        })
        laden()
    }

    override fun onResume() {
        super.onResume()
        // zurueck aus "Unbekannte Apps installieren" oder nach einer Installation
        if (warteschlange.isNotEmpty() && packageManager.canRequestPackageInstalls() && !laeuft) naechste()
    }

    private fun laden() {
        status.text = "Suche Updates …"
        Thread {
            val l = try { RosenwegUpdater.verfuegbar(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (l == null) { status.text = "Rosenweg nicht erreichbar"; return@runOnUiThread }
                updates = l
                if (l.isEmpty()) {
                    status.text = "Alle Rosenweg-Apps sind aktuell."
                    RosenwegUpdater.abmelden(this)
                    liste.text = ""; knopf.isEnabled = false
                } else {
                    status.text = if (l.size == 1) "1 Update" else "${l.size} Updates"
                    liste.text = l.joinToString("\n") { "• ${it.name}  ${it.versionName}" }
                    knopf.isEnabled = true
                }
            }
        }.start()
    }

    private fun starten() {
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
        if (u == null) { laeuft = false; laden(); return }
        laeuft = true
        status.text = "${u.name}: lade …"
        Thread {
            try {
                val f = RosenwegUpdater.laden(this, u)
                runOnUiThread { status.text = "${u.name}: installieren …" }
                installieren(f, u)
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "${u.name}: ${e.message ?: e.javaClass.simpleName}"
                    warteschlange.removeAt(0); laeuft = false
                    knopf.isEnabled = true
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
        pi.openSession(id).use { s ->
            f.inputStream().use { inp -> s.openWrite("apk", 0, f.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
            val rueck = PendingIntent.getBroadcast(this, id, Intent(this, InstallStatus::class.java).putExtra("paket", u.paket),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(rueck.intentSender)
        }
        f.delete()
    }

    /** Von InstallStatus: eine Installation ist fertig (oder abgebrochen). */
    internal fun fertig(paket: String, ok: Boolean, meldung: String?) {
        val u = warteschlange.firstOrNull { it.paket == paket } ?: return
        warteschlange.remove(u)
        status.text = if (ok) "${u.name}: aktualisiert" else "${u.name}: ${meldung ?: "nicht installiert"}"
        laeuft = false
        naechste()
    }

    override fun onStart() { super.onStart(); aktiv = this }
    override fun onDestroy() { if (aktiv === this) aktiv = null; super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        internal val warteschlange = mutableListOf<RosenwegUpdater.Update>()
        internal var laeuft = false
        // Nur schwach gehalten (InstallStatus meldet an die sichtbare Activity), damit keine
        // beendete Activity im Speicher haengen bleibt.
        private var aktivRef: WeakReference<UpdateActivity>? = null
        internal var aktiv: UpdateActivity?
            get() = aktivRef?.get()
            set(v) { aktivRef = v?.let { WeakReference(it) } }
    }
}
