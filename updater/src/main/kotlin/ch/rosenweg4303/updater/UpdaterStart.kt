package ch.rosenweg4303.updater

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Startet den Updater beim Start jeder App mit diesem Modul, ohne dass die App selbst etwas
 * aufrufen muss (wie androidx.startup). Nur das taegliche Pruefen einplanen; ob diese App
 * zustaendig ist, entscheidet die Pruefung.
 */
class UpdaterStart : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { c ->
            try {
                if (!RosenwegUpdater.geraetDa(c)) RosenwegUpdater.einrichten(c)
            } catch (e: Exception) {
                android.util.Log.w(RosenwegUpdater.TAG, "Start: ${e.javaClass.simpleName}")
            }
        }
        return true
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
