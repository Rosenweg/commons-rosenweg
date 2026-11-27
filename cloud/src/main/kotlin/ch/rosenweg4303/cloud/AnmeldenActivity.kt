package ch.rosenweg4303.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * "Rosenweg-Nextcloud" in den Einstellungen aller Rosenweg-Apps mit Backend (Kalender, Kontakte,
 * Dokumente, Galerie, Notizen, Paint, Diktiergeraet, Files, Kamera):
 *
 *     startActivity(Intent(this, ch.rosenweg4303.cloud.AnmeldenActivity::class.java))
 *
 * Zeigt den TATSAECHLICHEN Stand ([CloudStatus]): angemeldet als wer und wie (Rosenweg-Karte,
 * Rosenweg-Konto, geteiltes Telefon ueber Rosenweg Geraet), Nextcloud verbunden (sonst mit dem
 * Fehlertext), letzter Abgleich, Warteschlange beim Hochladen. Knoepfe "Nextcloud neu verbinden"
 * und "Abmelden".
 *
 * Es gibt nur EINEN Anmeldeweg (Stefan 27.11.2026): ist die App nicht angemeldet, oeffnet diese
 * Seite den gemeinsamen [RosenwegAnmeldeSchirm] (Karte oder Rosenweg-Konto), der danach den
 * Nextcloud-Zugang von der Anlage holt. Bricht man dort ab, schliesst sich auch diese Seite.
 * (Frueher: eigener Knopf "Anmelden" mit Rueckfall auf den Login-Flow der Nextcloud im Browser -
 * ein zweiter Weg, der vom Anmeldeschirm abwich.) Auf geteilten Telefonen kommt der Zugang von
 * Rosenweg Geraet; an- und abgemeldet wird dort.
 */
class AnmeldenActivity : Activity() {
    companion object {
        /** Eigener Erklaersatz oben (Standard: Hochladen in den persoenlichen Ordner). */
        const val EXTRA_BESCHREIBUNG = "ch.rosenweg4303.cloud.BESCHREIBUNG"
    }

    private lateinit var anmeldung: TextView
    private lateinit var verbindung: TextView
    private lateinit var abgleich: TextView
    private lateinit var warteschlange: TextView
    private lateinit var verbinden: Button
    private lateinit var abmelden: Button
    private lateinit var nochmal: Button
    @Volatile private var verbindet = false
    /** Anmeldeschirm ist offen (oder war es). */
    private var schirmGezeigt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rosenweg-Nextcloud"
        val rand = dp(24)
        anmeldung = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        verbindung = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(12), 0, 0) }
        abgleich = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        warteschlange = TextView(this).apply { setPadding(0, dp(8), 0, dp(16)) }
        verbinden = Button(this).apply { setOnClickListener { neuVerbinden() } }
        abmelden = Button(this).apply { text = "Abmelden"; setOnClickListener { abmelden() } }
        nochmal = Button(this).apply {
            text = "Fehlgeschlagene nochmal versuchen"
            setOnClickListener {
                Hochladen.anstossen(this@AnmeldenActivity, fehlerNochmal = true)
                anzeigen()
            }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(rand, rand, rand, rand)
                addView(TextView(context).apply {
                    // Kalender, Kontakte, Dokumente erklaeren selbst, was sie mit der Nextcloud tun
                    text = intent?.getStringExtra(EXTRA_BESCHREIBUNG)
                        ?: ("Dateien dieser App werden in deinen persönlichen Ordner der " +
                            "Rosenweg-Nextcloud hochgeladen (${Hochladen.PERSOENLICH}). Auf dem " +
                            "Telefon bleibt nur, was noch nicht oben ist.")
                    setPadding(0, 0, 0, dp(24))
                })
                addView(anmeldung)
                addView(verbindung)
                addView(abgleich)
                addView(warteschlange)
                addView(verbinden)
                addView(abmelden)
                addView(nochmal)
                // Rosenweg Updater (0.1.6): sofort pruefen, Ergebnis auf eigener Seite
                addView(Button(context).apply {
                    text = "Nach Updates suchen"
                    setOnClickListener { ch.rosenweg4303.updater.RosenwegUpdater.jetztPruefen(this@AnmeldenActivity) }
                })
            })
        })
    }

    override fun onResume() {
        super.onResume()
        if (RosenwegAnmeldung.anmeldungNoetig(this)) {
            if (schirmGezeigt) {
                // vom Anmeldeschirm ohne Anmeldung zurueck: diese Seite hat dann nichts zu zeigen
                finish()
                return
            }
            schirmGezeigt = true
            startActivity(Intent(this, RosenwegAnmeldeSchirm::class.java)
                .putExtra(RosenwegAnmeldeSchirm.EXTRA_NEXTCLOUD, true)
                .putExtra(RosenwegAnmeldeSchirm.EXTRA_ZURUECK_SCHLIESST_APP, false))
            return
        }
        anzeigen()
    }

    private fun anzeigen() {
        val geraet = RosenwegAnmeldung.geraetDa(this)
        val konto = KontoSpeicher(this).laden()
        val offen = Hochladen.ausstehend(this)
        val fehler = Hochladen.fehlgeschlagen(this)
        anmeldung.text = CloudStatus.anmeldung(this)
        verbindung.text = if (verbindet) "Nextcloud wird verbunden …" else CloudStatus.verbindung(this)
        abgleich.text = CloudStatus.abgleichText(this).orEmpty()
        abgleich.visibility = if (konto != null && abgleich.text.isNotEmpty()) TextView.VISIBLE else TextView.GONE
        warteschlange.text = buildString {
            if (offen > 0) append("$offen Datei(en) warten auf das Hochladen.")
            if (fehler > 0) {
                if (isNotEmpty()) append("\n")
                append("$fehler Datei(en) konnten nicht hochgeladen werden (bleiben auf dem Telefon):")
                Hochladen.fehlerTexte(this@AnmeldenActivity).take(5).forEach { append("\n• $it") }
            }
            if (geraet) {
                if (isNotEmpty()) append("\n\n")
                append("An- und abgemeldet wird am geteilten Telefon (Rosenweg Gerät).")
            }
        }
        nochmal.visibility = if (fehler > 0 && konto != null) Button.VISIBLE else Button.GONE
        // Geteiltes Telefon: Zugang und Abmelden gehoeren Rosenweg Geraet
        verbinden.visibility = if (geraet) Button.GONE else Button.VISIBLE
        abmelden.visibility = if (geraet) Button.GONE else Button.VISIBLE
        verbinden.isEnabled = !verbindet
        verbinden.text = if (konto == null) "Nextcloud verbinden" else "Nextcloud neu verbinden"
    }

    /** App-Passwort (neu) von der Anlage holen; ein altes wird danach widerrufen. */
    private fun neuVerbinden() {
        if (verbindet) return
        verbindet = true
        anzeigen()
        val alt = KontoSpeicher(this).laden()
        val app = applicationContext
        Thread {
            val meldung = try {
                val neu = NextcloudZugang.holenUndSpeichern(app)
                if (alt != null && alt.appPasswort != neu.appPasswort) {
                    try { WebDav(alt, NextcloudZugang.appName(app)).appPasswortWiderrufen() } catch (e: Exception) { /* egal */ }
                }
                "Nextcloud verbunden als ${neu.benutzer}"
            } catch (e: NextcloudZugang.Fehler) {
                "Nextcloud: ${e.message}"
            } catch (e: Exception) {
                "Keine Verbindung zum Rosenweg"
            }
            runOnUiThread {
                verbindet = false
                if (!isFinishing) {
                    Toast.makeText(this, meldung, Toast.LENGTH_LONG).show()
                    anzeigen()
                }
            }
        }.start()
    }

    private fun abmelden() {
        val konto = KontoSpeicher(this).laden()
        val offen = Hochladen.ausstehend(this) + Hochladen.fehlgeschlagen(this)
        if (offen > 0) {
            // Grundregel: nichts verlieren, was noch nicht in der Nextcloud ist
            Hochladen.anstossen(this)
            Toast.makeText(this, "Noch $offen Datei(en) nicht hochgeladen — erst danach abmelden.", Toast.LENGTH_LONG).show()
            return
        }
        // Nextcloud-Zugang und Anmeldung gehoeren zusammen (der Zugang kommt nach der Anmeldung von
        // selbst): Abmelden meldet die App ab, beim naechsten Oeffnen kommt der Anmeldeschirm.
        AlertDialog.Builder(this)
            .setMessage("Aus dieser App und von der Rosenweg-Nextcloud abmelden? Die Daten bleiben in der Nextcloud.")
            .setPositiveButton("Abmelden") { _, _ ->
                KontoSpeicher(this).loeschen()
                CloudStatus.nextcloud(this, null)
                if (konto != null) Thread { try { WebDav(konto, NextcloudZugang.appName(this)).appPasswortWiderrufen() } catch (e: Exception) { /* egal */ } }.start()
                RosenwegAnmeldung.abmelden(this)
                Toast.makeText(this, "Abgemeldet", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
