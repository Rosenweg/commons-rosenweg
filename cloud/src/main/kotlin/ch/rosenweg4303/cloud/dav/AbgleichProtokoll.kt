package ch.rosenweg4303.cloud.dav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Protokoll des Nextcloud-Abgleichs (je App und Art). Regel bei Konflikten: der Server gewinnt -
 * aber die lokale Fassung geht nicht still verloren: sie steht hier (mit dem vollstaendigen
 * iCalendar/vCard-Text) und kann in der App wiederhergestellt werden ([KalenderAbgleich.wiederherstellen],
 * [KontakteAbgleich.wiederherstellen]). Dazu eine Benachrichtigung, wenn die App das darf.
 *
 * Gespeichert app-privat als JSON (hoechstens [MAX] Eintraege, die neuesten zuerst).
 */
class AbgleichProtokoll(context: Context, private val art: NextcloudDav.Art) {
    enum class Typ { KONFLIKT, NUR_LESEN, FEHLER }

    data class Eintrag(
        val id: Long,
        val zeit: Long,
        val typ: Typ,
        val titel: String,
        val text: String,
        /** Adresse auf dem Server (fuer Wiederherstellen) */
        val href: String? = null,
        /** die verworfene lokale Fassung (iCalendar/vCard) */
        val daten: String? = null,
    )

    private val ctx = context.applicationContext
    private val datei = File(ctx.filesDir, "rosenweg-dav-protokoll-${art.name.lowercase()}.json")

    @Synchronized
    fun liste(): List<Eintrag> = try {
        if (!datei.exists()) emptyList() else {
            val a = JSONArray(datei.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Eintrag(o.getLong("id"), o.getLong("zeit"), Typ.valueOf(o.getString("typ")), o.optString("titel"),
                    o.optString("text"), o.optString("href").ifEmpty { null }, o.optString("daten").ifEmpty { null })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Protokoll unlesbar: ${e.message}")
        emptyList()
    }

    /** Anzahl offener Konflikte (fuer die Anzeige in den Einstellungen). */
    fun konflikte(): Int = liste().count { it.typ == Typ.KONFLIKT }

    @Synchronized
    fun eintragen(typ: Typ, titel: String, text: String, href: String? = null, daten: String? = null) {
        val neu = Eintrag(System.currentTimeMillis() * 10 + (0..9).random(), System.currentTimeMillis(), typ, titel, text, href, daten)
        speichern((listOf(neu) + liste()).take(MAX))
        Log.i(TAG, "${art.name} ${typ.name}: $titel - $text")
        if (typ != Typ.FEHLER) melden(neu)
    }

    @Synchronized
    fun entfernen(id: Long) = speichern(liste().filter { it.id != id })

    @Synchronized
    fun leeren() { datei.delete() }

    private fun speichern(l: List<Eintrag>) {
        val a = JSONArray()
        l.forEach { e ->
            a.put(JSONObject().put("id", e.id).put("zeit", e.zeit).put("typ", e.typ.name).put("titel", e.titel)
                .put("text", e.text).put("href", e.href ?: "").put("daten", e.daten ?: ""))
        }
        val tmp = File(datei.path + ".neu")
        tmp.writeText(a.toString())
        tmp.renameTo(datei)
    }

    /** Benachrichtigung (nur wenn erlaubt; sonst steht es nur im Protokoll). */
    private fun melden(e: Eintrag) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(KANAL, "Nextcloud-Abgleich", NotificationManager.IMPORTANCE_LOW))
            val start = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.putExtra(EXTRA_PROTOKOLL, art.name)
            val pi = start?.let { PendingIntent.getActivity(ctx, art.ordinal, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val was = if (art == NextcloudDav.Art.KALENDER) "Termin" else "Kontakt"
            val titel = when (e.typ) {
                Typ.KONFLIKT -> "$was in der Nextcloud geändert: ${e.titel}"
                else -> "$was nur lesbar: ${e.titel}"
            }
            val n = Notification.Builder(ctx, KANAL).setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(titel).setContentText(e.text).setStyle(Notification.BigTextStyle().bigText(
                    e.text + "\nDeine Fassung ist im Abgleich-Protokoll (Einstellungen) und lässt sich dort wiederherstellen."))
                .setAutoCancel(true).apply { if (pi != null) setContentIntent(pi) }.build()
            nm.notify(KANAL, art.ordinal, n)
        } catch (x: Exception) {
            Log.w(TAG, "Benachrichtigung: ${x.message}")
        }
    }

    companion object {
        private const val TAG = "RosenwegDav"
        private const val MAX = 50
        private const val KANAL = "rosenweg_dav_abgleich"
        /** Extra am Start-Intent der App aus der Benachrichtigung: Art (KALENDER/ADRESSEN). */
        const val EXTRA_PROTOKOLL = "ch.rosenweg4303.cloud.dav.PROTOKOLL"
    }
}
