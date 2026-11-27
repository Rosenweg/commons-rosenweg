package ch.rosenweg4303.cloud

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * Der tatsaechliche Stand der App fuer die Einstellungen (Stefan 27.11.2026: "Die Anmeldeseite
 * ist in den Settings nicht sauber verknuepft"): App-Anmeldung (wer, wie), Nextcloud verbunden
 * (sonst der letzte Fehler), letzter Abgleich. Alle Apps zeigen dasselbe:
 *
 *     settingsNextcloud.text = CloudStatus.kurz(this)
 *
 * und der Eintrag oeffnet [AnmeldenActivity] (die bei fehlender Anmeldung den gemeinsamen
 * [RosenwegAnmeldeSchirm] zeigt - kein zweiter Anmeldeweg).
 */
object CloudStatus {
    private const val PREFS = "rosenweg_cloud_status"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Nextcloud-Verbindung: [fehler] null = in Ordnung (z.B. nach erfolgreichem Holen/Abgleich). */
    fun nextcloud(c: Context, fehler: String?) {
        p(c).edit().putString("nc_fehler", fehler).putLong("nc_zeit", System.currentTimeMillis()).apply()
    }

    fun nextcloudFehler(c: Context): String? = p(c).getString("nc_fehler", null)

    /** Ergebnis eines Abgleichs (Kalender, Kontakte, Hochladen ...). */
    fun abgleich(c: Context, ok: Boolean, text: String) {
        p(c).edit().putLong("ab_zeit", System.currentTimeMillis()).putBoolean("ab_ok", ok).putString("ab_text", text).apply()
        if (ok) nextcloud(c, null)
    }

    /** (Zeitpunkt, ok, Text) des letzten Abgleichs, oder null. */
    fun letzterAbgleich(c: Context): Triple<Long, Boolean, String>? {
        val z = p(c).getLong("ab_zeit", 0)
        return if (z == 0L) null else Triple(z, p(c).getBoolean("ab_ok", true), p(c).getString("ab_text", "").orEmpty())
    }

    /** "Angemeldet als Erika (Rosenweg-Karte|QR-Code|Rosenweg-Konto)" / "... (über Rosenweg Gerät)" / "Nicht angemeldet". */
    fun anmeldung(c: Context): String {
        val konto = KontoSpeicher(c).laden()
        if (RosenwegAnmeldung.geraetDa(c)) {
            return if (konto != null) "Geteiltes Telefon: angemeldet als ${konto.benutzer} (über Rosenweg Gerät)"
            else "Geteiltes Telefon: niemand angemeldet (Anmeldung am Sperrbildschirm)"
        }
        if (!RosenwegAnmeldung.angemeldet(c)) return "Nicht angemeldet"
        val wer = RosenwegAnmeldung.name(c)?.takeIf { it.isNotBlank() } ?: RosenwegAnmeldung.email(c) ?: "?"
        val wie = when {
            !RosenwegAnmeldung.mitKarte(c) -> "Rosenweg-Konto"
            KartenSitzung.weg(c) == "mit QR-Code" -> "QR-Code"
            else -> "Rosenweg-Karte"
        }
        return "Angemeldet als $wer ($wie)"
    }

    /** "Nextcloud verbunden (stefan)" / "Nextcloud nicht verbunden: <Fehler>". */
    fun verbindung(c: Context): String {
        val konto = KontoSpeicher(c).laden()
        val fehler = nextcloudFehler(c)
        return when {
            konto != null && fehler == null -> "Nextcloud verbunden (${konto.benutzer})"
            konto != null -> "Nextcloud verbunden (${konto.benutzer}), aber: $fehler"
            fehler != null -> "Nextcloud nicht verbunden: $fehler"
            else -> "Nextcloud nicht verbunden"
        }
    }

    fun abgleichText(c: Context): String? = letzterAbgleich(c)?.let { (z, ok, t) ->
        "Letzter Abgleich: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(z)) +
            (if (ok) "" else " – Fehler") + (if (t.isNotEmpty()) " ($t)" else "")
    }

    /** Eine Zeile fuer den Einstellungseintrag. */
    fun kurz(c: Context): String {
        val konto = KontoSpeicher(c).laden()
        if (!RosenwegAnmeldung.geraetDa(c) && !RosenwegAnmeldung.angemeldet(c)) return "Nicht angemeldet – tippen zum Anmelden"
        return listOfNotNull(anmeldung(c), verbindung(c), if (konto != null) abgleichText(c) else null).joinToString("\n")
    }
}
