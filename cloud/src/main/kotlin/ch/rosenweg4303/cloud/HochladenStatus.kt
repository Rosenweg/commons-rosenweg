package ch.rosenweg4303.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Fuer Rosenweg Geraet (nur Rosenweg-Signatur): Was wartet noch aufs Hochladen?
 * Abmelden am geteilten Telefon geht erst, wenn alles in der Nextcloud ist
 * (Entscheid Stefan 24.11.2026) — sonst loeschte das Abmelden Dateien, die es
 * nur auf dem Telefon gibt.
 *
 *   content://<paket>.rosenweg.hochladen
 *   call("status")    -> {ausstehend, fehlgeschlagen}
 *   call("anstossen") -> Hochladen jetzt versuchen (auch fehlgeschlagene nochmal)
 */
class HochladenStatus : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = context!!
        if (c.checkCallingPermission("ch.rosenweg4303.permission.ANLAGE") != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Nur Rosenweg-Apps")
        }
        val b = Bundle()
        when (method) {
            "status" -> {
                b.putInt("ausstehend", Hochladen.ausstehend(c))
                b.putInt("fehlgeschlagen", Hochladen.fehlgeschlagen(c))
            }
            "anstossen" -> Hochladen.anstossen(c, fehlerNochmal = true)
        }
        return b
    }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
