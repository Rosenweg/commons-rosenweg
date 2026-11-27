package ch.rosenweg4303.cloud.dav

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Das [AbgleichProtokoll] in der App zeigen (Einstellungen -> "Abgleich-Protokoll"). Bei einem
 * Konflikt laesst sich die verworfene eigene Fassung wiederherstellen (ueberschreibt die Fassung in
 * der Nextcloud) oder verwerfen. [wiederherstellen] laeuft im Hintergrund (Netz).
 */
object ProtokollAnsicht {
    fun zeigen(a: Activity, art: NextcloudDav.Art, wiederherstellen: (AbgleichProtokoll.Eintrag) -> Boolean, danach: () -> Unit = {}) {
        val p = AbgleichProtokoll(a, art)
        val liste = p.liste()
        if (liste.isEmpty()) {
            AlertDialog.Builder(a).setTitle("Abgleich-Protokoll").setMessage("Keine Konflikte oder Meldungen.")
                .setPositiveButton("OK", null).show()
            return
        }
        val f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val zeilen = liste.map { e ->
            val typ = when (e.typ) {
                AbgleichProtokoll.Typ.KONFLIKT -> "Konflikt"
                AbgleichProtokoll.Typ.NUR_LESEN -> "Nur lesbar"
                AbgleichProtokoll.Typ.FEHLER -> "Fehler"
            }
            "${f.format(Date(e.zeit))} – $typ: ${e.titel}\n${e.text}"
        }.toTypedArray()
        AlertDialog.Builder(a).setTitle("Abgleich-Protokoll")
            .setItems(zeilen) { _, i -> eintrag(a, p, liste[i], wiederherstellen, danach) }
            .setNeutralButton("Alles leeren") { _, _ -> p.leeren(); danach() }
            .setPositiveButton("Schliessen", null).show()
    }

    private fun eintrag(a: Activity, p: AbgleichProtokoll, e: AbgleichProtokoll.Eintrag,
                        wiederherstellen: (AbgleichProtokoll.Eintrag) -> Boolean, danach: () -> Unit) {
        val b = AlertDialog.Builder(a).setTitle(e.titel).setMessage(e.text)
            .setNegativeButton("Entfernen") { _, _ -> p.entfernen(e.id); danach() }
            .setNeutralButton("Abbrechen", null)
        if (e.daten != null && e.href != null) {
            b.setMessage(e.text + "\n\n„Meine Fassung“ schreibt deine verworfene Änderung in die Nextcloud (überschreibt die dortige Fassung).")
            b.setPositiveButton("Meine Fassung") { _, _ ->
                Thread {
                    val ok = try { wiederherstellen(e) } catch (x: Exception) { false }
                    a.runOnUiThread {
                        Toast.makeText(a, if (ok) "Wiederhergestellt" else "Wiederherstellen nicht möglich", Toast.LENGTH_LONG).show()
                        danach()
                    }
                }.start()
            }
        }
        b.show()
    }
}
