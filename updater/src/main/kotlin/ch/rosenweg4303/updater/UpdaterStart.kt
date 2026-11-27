package ch.rosenweg4303.updater

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Startet den Updater beim Start jeder App mit diesem Modul, ohne dass die App selbst etwas
 * aufrufen muss (wie androidx.startup). Nur das taegliche Pruefen einplanen; ob diese App
 * zustaendig ist (und ob nur der Stand gemeldet wird), entscheidet die Pruefung.
 *
 * Erst NACH allen ContentProvidern einplanen (0.1.5): WorkManager richtet sich selbst ueber den
 * InitializationProvider von androidx.startup ein. Im zusammengefuehrten Manifest steht dieser
 * Provider hinter UpdaterStart (beide ohne initOrder, Android startet sie in dieser Reihenfolge),
 * also war WorkManager in onCreate noch nicht da: "Start: IllegalStateException" in jeder App
 * mit diesem Modul (gesehen 27.11.2026 in Rosenweg Virtuelle Computer auf dem A37; Karten und
 * Salz haben dieselbe Reihenfolge), der Updater plante nie. Der Hauptthread arbeitet Nachrichten erst ab, wenn alle Provider und
 * Application.onCreate durch sind.
 */
class UpdaterStart : ContentProvider() {
    override fun onCreate(): Boolean {
        val c = context?.applicationContext ?: return true
        Handler(Looper.getMainLooper()).post { einplanen(c) }
        return true
    }

    private fun einplanen(c: android.content.Context) {
        zurueckNachRecht(c)
        try {
            // 0.1.6 auch auf geteilten Telefonen: dort installiert Rosenweg Geraet, die Pruefung
            // meldet nur den Versionsstand ans NOC.
            RosenwegUpdater.einrichten(c)
        } catch (e: Exception) {
            android.util.Log.w(RosenwegUpdater.TAG, "Start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    /**
     * 0.1.8: Wurde "Apps aus dieser Quelle installieren" gerade erteilt (und dabei der Prozess beendet),
     * oeffnet die erste sichtbare Activity der App wieder die Update-Seite, die dort weitermacht.
     */
    private fun zurueckNachRecht(c: android.content.Context) {
        val app = c as? android.app.Application ?: return
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                if (a is UpdateActivity || !RechtMerker.wartet(a)) return
                if (!a.packageManager.canRequestPackageInstalls()) return
                val danach = RechtMerker.danachInstallieren(a)
                RechtMerker.vergessen(a)
                android.util.Log.i(RosenwegUpdater.TAG, "Recht erteilt: zurueck zur Update-Seite")
                a.startActivity(android.content.Intent(a, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_NACH_RECHT, danach))
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivityStopped(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
