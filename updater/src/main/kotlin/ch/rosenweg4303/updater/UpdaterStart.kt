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
        try {
            // 0.1.6 auch auf geteilten Telefonen: dort installiert Rosenweg Geraet, die Pruefung
            // meldet nur den Versionsstand ans NOC.
            RosenwegUpdater.einrichten(c)
        } catch (e: Exception) {
            android.util.Log.w(RosenwegUpdater.TAG, "Start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
