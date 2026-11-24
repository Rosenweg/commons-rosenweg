package ch.rosenweg4303.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "Rosenweg-Nextcloud" fuer Apps, die selbst hochladen (Kamera, Diktiergeraet,
 * Notizen, Paint). Jede App bringt sie ueber die Bibliothek mit und oeffnet sie
 * aus ihren Einstellungen:
 *
 *     startActivity(Intent(this, ch.rosenweg4303.cloud.AnmeldenActivity::class.java))
 *
 * Anmeldung ueber den Login-Flow der Nextcloud (Authentik im Browser); die App
 * bekommt ein eigenes App-Passwort. Abmelden geht erst, wenn alles oben ist.
 */
class AnmeldenActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var warteschlange: TextView
    private lateinit var knopf: Button
    private lateinit var nochmal: Button
    private var login: LoginFlow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rosenweg-Nextcloud"
        val rand = dp(24)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        warteschlange = TextView(this).apply { setPadding(0, dp(12), 0, dp(24)) }
        knopf = Button(this)
        nochmal = Button(this).apply {
            text = "Fehlgeschlagene nochmal versuchen"
            setOnClickListener {
                Hochladen.anstossen(this@AnmeldenActivity, fehlerNochmal = true)
                anzeigen()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(rand, rand, rand, rand)
            addView(TextView(context).apply {
                text = "Dateien dieser App werden in deinen persönlichen Ordner der " +
                    "Rosenweg-Nextcloud hochgeladen (${Hochladen.PERSOENLICH}). Auf dem " +
                    "Telefon bleibt nur, was noch nicht oben ist."
                setPadding(0, 0, 0, dp(24))
            })
            addView(status)
            addView(warteschlange)
            addView(knopf)
            addView(nochmal)
        })
    }

    override fun onResume() {
        super.onResume()
        anzeigen()
    }

    override fun onDestroy() {
        login?.abgebrochen = true
        super.onDestroy()
    }

    private fun anzeigen() {
        val konto = KontoSpeicher(this).laden()
        val offen = Hochladen.ausstehend(this)
        val fehler = Hochladen.fehlgeschlagen(this)
        status.text = when {
            login != null -> "Warte auf die Anmeldung im Browser …"
            konto != null -> "Angemeldet als ${konto.benutzer}"
            else -> "Nicht angemeldet"
        }
        warteschlange.text = buildString {
            append(if (offen == 0) "Alles hochgeladen." else "$offen Datei(en) warten auf das Hochladen.")
            if (fehler > 0) append("\n$fehler Datei(en) konnten nicht hochgeladen werden (bleiben auf dem Telefon).")
        }
        nochmal.visibility = if (fehler > 0 && konto != null) Button.VISIBLE else Button.GONE
        knopf.isEnabled = login == null
        if (konto == null) {
            knopf.text = "Anmelden"
            knopf.setOnClickListener { anmelden() }
        } else {
            knopf.text = "Abmelden"
            knopf.setOnClickListener { abmelden(konto, offen + fehler) }
        }
    }

    private fun anmelden() {
        val flow = LoginFlow(appName())
        login = flow
        anzeigen()
        Thread {
            try {
                flow.starten()
                runOnUiThread { browser(flow.anmeldeAdresse) }
                val konto = flow.warten()
                KontoSpeicher(this).speichern(konto)
                Hochladen.anstossen(this)
                runOnUiThread {
                    login = null
                    // zurueck in die App holen, falls der Browser noch vorne ist
                    startActivity(Intent(this, AnmeldenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    anzeigen()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    login = null
                    anzeigen()
                    if (!flow.abgebrochen) Toast.makeText(this, "Nextcloud: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun abmelden(konto: Konto, offen: Int) {
        if (offen > 0) {
            // Grundregel: nichts verlieren, was noch nicht in der Nextcloud ist
            Hochladen.anstossen(this)
            Toast.makeText(this, "Noch $offen Datei(en) nicht hochgeladen — erst danach abmelden.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Von der Rosenweg-Nextcloud abmelden?")
            .setPositiveButton("Abmelden") { _, _ ->
                KontoSpeicher(this).loeschen()
                Thread { WebDav(konto, appName()).appPasswortWiderrufen() }.start()
                anzeigen()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun browser(adresse: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(adresse)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_LONG).show()
        }
    }

    private fun appName() = "${applicationInfo.loadLabel(packageManager)} (Android)"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
