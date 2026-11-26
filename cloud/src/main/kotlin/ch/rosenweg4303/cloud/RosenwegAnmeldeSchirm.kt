package ch.rosenweg4303.cloud

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Anmeldeschirm der Rosenweg-Apps auf privaten Handys (ohne Rosenweg Geraet), Stefan 26.11.2026:
 * derselbe Schirm wie der Sperrbildschirm der geteilten Telefone (Rosenweg Geraet,
 * SperrActivity) - blauer Grund, Rosenweg-Logo, "Rosenweg-Karte hinten ans Telefon halten",
 * darunter "Mit Rosenweg-Konto anmelden". Die Karte allein meldet an (ohne PIN; die PIN gibt es
 * nur auf geteilten Telefonen). Nicht hier: QR-Code (der ist fuer geteilte Telefone, auf dem
 * eigenen Handy ersetzt ihn der Browser) und Notruf (das eigene Handy ist nicht gesperrt).
 *
 * Gilt nur fuer DIESE App: jede Rosenweg-App meldet sich selbst an.
 *
 * Einbinden - in onResume der Hauptaktivitaet:
 *
 *     if (RosenwegAnmeldeSchirm.zeigenFallsNoetig(this)) return
 *
 * Danach liefern RosenwegAnmeldung.zugangsToken / name / abmelden fuer beide Wege dasselbe.
 */
class RosenwegAnmeldeSchirm : Activity() {
    companion object {
        private const val TAG = "RosenwegAnmeldeSchirm"
        private const val BLAU = "#1E3A8A"
        private const val HELLBLAU = "#BFDBFE"
        private const val KNOPF = "#2563EB"
        private const val KNOPF_DUNKEL = "#1E40AF"
        private const val ROT = "#FCA5A5"
        private const val GRUEN = "#BBF7D0"

        /** Reader-Modus wie Rosenweg Geraet (NfcKarte.LESER_FLAGS): alle Kartentypen, nur die UID. */
        const val LESER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        /** Anmeldeschirm oeffnen, wenn ohne Geraet niemand angemeldet ist. true = geoeffnet. */
        @JvmStatic
        fun zeigenFallsNoetig(a: Activity): Boolean {
            if (!RosenwegAnmeldung.anmeldungNoetig(a)) return false
            a.startActivity(Intent(a, RosenwegAnmeldeSchirm::class.java))
            return true
        }

        /** UID der Karte als Hex in Grossbuchstaben (= nfc_id in UniFi Access), null ohne Karte. */
        @JvmStatic
        fun uid(tag: Tag?): String? {
            val id = tag?.id ?: return null
            if (id.isEmpty()) return null
            return id.joinToString("") { "%02X".format(it) }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var kopf: TextView
    private lateinit var logo: ImageView
    private lateinit var kartenHinweis: TextView
    private lateinit var hinweis: TextView
    private lateinit var meldung: TextView
    private lateinit var nfcKnopf: Button
    private lateinit var kontoKnopf: Button
    private lateinit var schritte: LinearLayout
    private lateinit var schrittRahmen: ScrollView
    private lateinit var obenPlatz: View
    private lateinit var untenPlatz: View
    private val zeilen = mutableListOf<TextView>()
    @Volatile private var laeuft = false
    @Volatile private var prueft = false
    private var zuletzt: String? = null
    private var zuletztUm = 0L

    private val appName: String by lazy {
        try { packageManager.getApplicationLabel(applicationInfo).toString() } catch (e: Exception) { "Rosenweg" }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor(BLAU)
        window.navigationBarColor = Color.parseColor(BLAU)
        setContentView(baue())
    }

    override fun onResume() {
        super.onResume()
        if (!RosenwegAnmeldung.anmeldungNoetig(this)) {   // z.B. zurueck aus dem Browser, angemeldet
            fertig()
            return
        }
        laeuft = true
        if (!prueft) grundzustand()
        leserEin()
    }

    override fun onPause() {
        laeuft = false
        leserAus()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ohne Anmeldung kann die App nichts: zurueck schliesst sie.
        finishAffinity()
    }

    private fun fertig() {
        setResult(RESULT_OK)
        finish()
    }

    // ─── Karte ──────────────────────────────────────────────────────────────

    private fun nfc(): NfcAdapter? = NfcAdapter.getDefaultAdapter(this)

    private fun leserEin() {
        val n = nfc() ?: return
        if (!n.isEnabled) return
        try {
            n.enableReaderMode(this, { tag -> karte(uid(tag)) }, LESER_FLAGS, null)
        } catch (e: Exception) {
            Log.w(TAG, "Kartenleser: ${e.javaClass.simpleName}")
        }
    }

    private fun leserAus() {
        try { nfc()?.disableReaderMode(this) } catch (e: Exception) { }
    }

    /** Aus dem Leser-Thread. Dieselbe Karte innert 4 s nur einmal (der Leser meldet sie mehrfach). */
    private fun karte(uid: String?) {
        if (uid == null || !laeuft || prueft) return
        val jetzt = SystemClock.elapsedRealtime()
        if (uid == zuletzt && jetzt - zuletztUm < 4000) return
        zuletzt = uid
        zuletztUm = jetzt
        prueft = true
        ui.post { ablaufZeigen() }
        Thread {
            try {
                KartenSitzung.anmelden(applicationContext, uid)
                ui.post {
                    schritt(0, true, null)
                    schritt(1, true, KartenSitzung.name(this)?.takeIf { it.isNotEmpty() }?.let { "angemeldet als $it" })
                    ui.postDelayed({ prueft = false; fertig() }, 800)
                }
            } catch (e: KartenSitzung.Fehler) {
                ui.post { fehler(e.message ?: "Anmelden fehlgeschlagen") }
            } catch (e: Exception) {
                Log.w(TAG, "Karte: ${e.javaClass.simpleName}")
                ui.post { fehler("Keine Verbindung zum Rosenweg") }
            }
        }.start()
    }

    // ─── Zustaende ──────────────────────────────────────────────────────────

    /** Wie der Sperrbildschirm: Logo gross, Karte, Konto-Knopf. */
    private fun grundzustand() {
        kopf.text = "$appName · zum Benutzen anmelden"
        kopf.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        kopf.setTextColor(Color.parseColor(HELLBLAU))
        schrittRahmen.visibility = View.GONE
        obenPlatz.visibility = View.VISIBLE
        untenPlatz.visibility = View.VISIBLE
        logoGroesse(230)
        kontoKnopf.visibility = View.VISIBLE
        meldung.visibility = View.GONE
        val n = nfc()
        when {
            n == null -> {
                kartenHinweis.visibility = View.GONE
                nfcKnopf.visibility = View.GONE
                hinweis.text = "Dieses Handy kann keine Karten lesen.\nMelde dich unten mit dem Rosenweg-Konto an."
            }
            !n.isEnabled -> {
                kartenHinweis.visibility = View.VISIBLE
                kartenHinweis.text = "NFC ist ausgeschaltet"
                nfcKnopf.visibility = View.VISIBLE
                hinweis.text = "Schalte NFC ein, um dich mit der Rosenweg-Karte anzumelden,\noder melde dich unten mit dem Rosenweg-Konto an."
            }
            else -> {
                kartenHinweis.visibility = View.VISIBLE
                kartenHinweis.text = "Rosenweg-Karte\nhinten ans Telefon halten"
                nfcKnopf.visibility = View.GONE
                hinweis.text = "Oder unten mit dem Rosenweg-Konto anmelden."
            }
        }
        hinweis.visibility = View.VISIBLE
    }

    /** Karte erkannt: Schritte wie beim Anmelden am geteilten Telefon. */
    private fun ablaufZeigen() {
        kopf.text = "Anmelden"
        kopf.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        kopf.setTextColor(Color.WHITE)
        for (v in listOf(kartenHinweis, hinweis, meldung, nfcKnopf, kontoKnopf, obenPlatz, untenPlatz)) v.visibility = View.GONE
        logoGroesse(110)
        schritte.removeAllViews()
        zeilen.clear()
        for (t in listOf("Rosenweg-Karte prüfen", "Anmeldung für $appName")) {
            val z = text(17f, HELLBLAU, false)
            z.gravity = Gravity.START
            z.setPadding(0, dp(5), 0, dp(5))
            z.tag = t
            z.text = "○  $t"
            schritte.addView(z)
            zeilen.add(z)
        }
        schrittRahmen.visibility = View.VISIBLE
        laufend(0, "Karte erkannt")
    }

    private fun laufend(i: Int, erklaerung: String?) {
        val z = zeilen.getOrNull(i) ?: return
        z.text = "⏳  ${z.tag}" + (erklaerung?.let { "\n     $it" } ?: "")
        z.setTextColor(Color.WHITE)
        z.setTypeface(null, Typeface.BOLD)
    }

    private fun schritt(i: Int, ok: Boolean, erklaerung: String?) {
        val z = zeilen.getOrNull(i) ?: return
        z.text = (if (ok) "✓  " else "✗  ") + z.tag + (erklaerung?.takeIf { it.isNotEmpty() }?.let { "\n     $it" } ?: "")
        z.setTextColor(Color.parseColor(if (ok) GRUEN else ROT))
        z.setTypeface(null, Typeface.NORMAL)
    }

    /** Wie der Sperrbildschirm: Schritt rot mit Ursache, nach 8 s zurueck. */
    private fun fehler(text: String) {
        schritt(0, false, "$text – bitte nochmals versuchen.")
        ui.postDelayed({
            prueft = false
            if (!isFinishing) grundzustand()
        }, 8000)
    }

    // ─── Oberflaeche (ohne Layout-Dateien, wie Rosenweg Geraet) ─────────────

    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(sp: Float, farbe: String, fett: Boolean) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(farbe))
        gravity = Gravity.CENTER
        if (fett) typeface = Typeface.DEFAULT_BOLD
    }

    private fun knopf(beschriftung: String, farbe: String) = Button(this).apply {
        text = beschriftung
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.parseColor(farbe))
            cornerRadius = dp(14).toFloat()
            setStroke(maxOf(1, dp(1.2f)), Color.WHITE)   // feiner weisser Rand wie am Sperrbildschirm
        }
    }

    private fun logoGroesse(groesse: Int) {
        logo.layoutParams = logo.layoutParams.apply {
            width = dp(groesse)
            height = dp(groesse)
        }
    }

    private fun baue(): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor(BLAU))
            setPadding(dp(24), dp(40), dp(24), dp(24))
            fitsSystemWindows = true
        }
        kopf = text(15f, HELLBLAU, false).apply { setPadding(0, dp(4), 0, dp(18)) }
        l.addView(kopf)

        obenPlatz = View(this)
        l.addView(obenPlatz, LinearLayout.LayoutParams(1, 0, 1f))
        logo = ImageView(this).apply {
            setImageResource(R.drawable.rosenweg_anmelde_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Rosenweg"
        }
        l.addView(logo, LinearLayout.LayoutParams(dp(230), dp(230)))

        schritte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        schrittRahmen = ScrollView(this).apply {
            addView(schritte)
            visibility = View.GONE
        }
        l.addView(schrittRahmen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        kartenHinweis = text(20f, "#FFFFFF", true).apply { setPadding(0, dp(20), 0, 0) }
        l.addView(kartenHinweis)
        hinweis = text(14f, HELLBLAU, false).apply { setPadding(0, dp(18), 0, 0) }
        l.addView(hinweis)
        meldung = text(15f, ROT, false).apply {
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        l.addView(meldung)

        untenPlatz = View(this)
        l.addView(untenPlatz, LinearLayout.LayoutParams(1, 0, 1f))

        nfcKnopf = knopf("NFC einschalten", KNOPF_DUNKEL).apply {
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        }
        l.addView(nfcKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })

        kontoKnopf = knopf("Mit Rosenweg-Konto anmelden", KNOPF).apply {
            setOnClickListener { RosenwegAnmeldung.starten(this@RosenwegAnmeldeSchirm) }
        }
        l.addView(kontoKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

        val fuss = text(12f, HELLBLAU, false).apply {
            text = "Die Anmeldung gilt nur für $appName."
            setPadding(0, dp(8), 0, 0)
        }
        l.addView(fuss)
        return l
    }
}
