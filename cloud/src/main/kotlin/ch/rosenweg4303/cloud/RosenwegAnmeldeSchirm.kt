package ch.rosenweg4303.cloud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.NfcAdapter
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ch.rosenweg4303.anmeldeschirm.AnmeldeOberflaeche as O
import ch.rosenweg4303.anmeldeschirm.Kartenleser
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings

/**
 * Anmeldeschirm der Rosenweg-Apps auf privaten Handys (ohne Rosenweg Geraet), Stefan 26.11.2026:
 * derselbe Schirm wie der Sperrbildschirm der geteilten Telefone (Rosenweg Geraet,
 * SperrActivity). Aussehen, Texte und Kartenleser kommen aus dem Modul anmeldeschirm, das auch
 * Geraet benutzt. Blauer Grund, Rosenweg-Abzeichen, "Rosenweg-Karte hinten ans Telefon halten",
 * darunter "Mit Rosenweg-Konto anmelden" und "QR-Code scannen" - in dieser Reihenfolge wie am
 * Sperrbildschirm. Die Karte allein meldet an (ohne PIN; die PIN gibt es nur auf geteilten
 * Telefonen). Nicht hier: Notruf (das eigene Handy ist nicht gesperrt) und "Kamera wechseln"
 * (gescannt wird ein Bildschirm, dafuer reicht die hintere Kamera).
 *
 * QR-Code (Stefan 27.11.2026): Die Person ist auf einem ANDEREN Geraet im Portal angemeldet (z.B.
 * am Computer), oeffnet dort rosenweg4303.ch/smartphones -> "App auf eigenem Handy" und haelt die
 * Kamera dieses Handys auf den Code. Einmalig, kurz gueltig, an die Person gebunden; die App
 * bekommt ihre eigene Sitzung wie mit der Karte ([KartenSitzung.anmeldenMitQr]). Die Kamera laeuft
 * nur auf Wunsch (das Recht wird erst beim Tippen erfragt) und hoechstens 60 s; solange ist der
 * Kartenleser aus (bei manchen Handys sitzt die NFC-Antenne neben der Kamera).
 *
 * Gilt nur fuer DIESE App: jede Rosenweg-App meldet sich selbst an.
 *
 * Einbinden - in onResume der Hauptaktivitaet:
 *
 *     if (RosenwegAnmeldeSchirm.zeigenFallsNoetig(this)) return
 *
 * Apps mit der Rosenweg-Nextcloud (Stefan 26.11.2026: "nach der Anmeldung laeuft sofort alles"):
 *
 *     if (RosenwegAnmeldeSchirm.zeigenFallsNoetig(this, nextcloud = true)) return
 *
 * Dann holt der Schirm nach der Anmeldung gleich den Nextcloud-Zugang ([NextcloudZugang],
 * Schritt "Nextcloud verbinden") und speichert das Konto; ist die App schon angemeldet, aber ohne
 * Zugang, wird er im Hintergrund nachgeholt. Kein "Anmelden" in den Einstellungen noetig.
 *
 * Flutter-Apps (rosenweg_basis) starten ihn auf Android ueber ihr Plugin
 * (startActivityForResult; RESULT_OK = angemeldet).
 *
 * Danach liefern RosenwegAnmeldung.zugangsToken / name / abmelden fuer beide Wege dasselbe.
 */
class RosenwegAnmeldeSchirm : Activity() {
    companion object {
        private const val TAG = "RosenwegAnmeldeSchirm"

        /**
         * false: "Zurueck" schliesst nur den Schirm (die App geht auch ohne Anmeldung, z.B. Kamera mit
         * Nextcloud). Standard true: ohne Anmeldung kann die App nichts, "Zurueck" schliesst sie.
         */
        const val EXTRA_ZURUECK_SCHLIESST_APP = "ch.rosenweg4303.cloud.ZURUECK_SCHLIESST_APP"

        /** true: nach der Anmeldung den Nextcloud-Zugang holen und speichern (fehlt er noch). */
        const val EXTRA_NEXTCLOUD = "ch.rosenweg4303.cloud.NEXTCLOUD"

        private const val RECHT_KAMERA = 43
        private const val KAMERA_MS = 60_000L
        /** Wo der Anmelde-QR fuer Apps steht. */
        private const val QR_SEITE = "rosenweg4303.ch/smartphones"

        /**
         * Anmeldeschirm oeffnen, wenn ohne Geraet niemand angemeldet ist. true = geoeffnet.
         * [nextcloud]: App nutzt die Rosenweg-Nextcloud - Zugang nach der Anmeldung gleich holen
         * bzw. (schon angemeldet) im Hintergrund nachholen.
         */
        @JvmStatic
        @JvmOverloads
        fun zeigenFallsNoetig(a: Activity, nextcloud: Boolean = false): Boolean {
            if (!RosenwegAnmeldung.anmeldungNoetig(a)) {
                if (nextcloud) NextcloudZugang.nachholenFallsNoetig(a)
                return false
            }
            a.startActivity(Intent(a, RosenwegAnmeldeSchirm::class.java).putExtra(EXTRA_NEXTCLOUD, nextcloud))
            return true
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
    private lateinit var qrKnopf: Button
    private lateinit var kameraRahmen: FrameLayout
    private lateinit var kamera: BarcodeView
    private lateinit var schritte: LinearLayout
    private lateinit var schrittRahmen: ScrollView
    private lateinit var obenPlatz: View
    private lateinit var untenPlatz: View
    private val zeilen = mutableListOf<TextView>()
    @Volatile private var laeuft = false
    @Volatile private var prueft = false
    /** Nach der Anmeldung: Nextcloud-Zugang wird geholt. */
    @Volatile private var holtNextcloud = false
    private val entpreller = Kartenleser.Entpreller()
    /** QR-Scan laeuft (Kamera an, Kartenleser aus). */
    private var kameraAn = false
    private var zuletztGescannt: String? = null
    private var zuletztGescanntUm = 0L
    private val kameraZeitAus = Runnable { kameraStoppen() }

    private val appName: String by lazy {
        try { packageManager.getApplicationLabel(applicationInfo).toString() } catch (e: Exception) { "Rosenweg" }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor(O.BLAU)
        window.navigationBarColor = Color.parseColor(O.BLAU)
        setContentView(baue())
    }

    override fun onResume() {
        super.onResume()
        if (holtNextcloud) return
        if (!RosenwegAnmeldung.anmeldungNoetig(this)) {   // z.B. zurueck aus dem Browser, angemeldet
            fertig()
            return
        }
        laeuft = true
        if (!prueft) grundzustand()
        if (kameraAn) kameraStarten() else leserEin()
    }

    override fun onPause() {
        laeuft = false
        leserAus()
        if (kameraAn) kamera.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(kameraZeitAus)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kamera an: zurueck schliesst nur die Kamera
        if (kameraAn && !prueft) {
            kameraStoppen()
            return
        }
        // Schon angemeldet (Nextcloud wird noch geholt): nur den Schirm schliessen, das Holen laeuft weiter
        if (!RosenwegAnmeldung.anmeldungNoetig(this)) {
            fertigSchliessen()
            return
        }
        // Ohne Anmeldung kann die App nichts: zurueck schliesst sie.
        setResult(RESULT_CANCELED)
        if (intent?.getBooleanExtra(EXTRA_ZURUECK_SCHLIESST_APP, true) == false) finish() else finishAffinity()
    }

    /** Angemeldet: falls gewuenscht noch den Nextcloud-Zugang holen, dann schliessen. */
    private fun fertig() {
        if (intent?.getBooleanExtra(EXTRA_NEXTCLOUD, false) == true && !RosenwegAnmeldung.geraetDa(this) &&
            KontoSpeicher(this).laden() == null
        ) {
            nextcloudHolen()
            return
        }
        fertigSchliessen()
    }

    private fun fertigSchliessen() {
        setResult(RESULT_OK)
        finish()
    }

    // ─── Nextcloud ──────────────────────────────────────────────────────────

    /** Schritt "Nextcloud verbinden": App-Passwort von der Anlage ([NextcloudZugang]). */
    private fun nextcloudHolen() {
        if (holtNextcloud) return
        holtNextcloud = true
        prueft = true
        leserAus()
        if (zeilen.isEmpty()) {   // Weg ueber das Rosenweg-Konto (Browser): Schritte jetzt zeigen
            ablaufZeigen(listOf("Anmeldung für $appName"))
            O.schrittStand(zeilen[0], O.OK, RosenwegAnmeldung.name(this)?.takeIf { it.isNotEmpty() }?.let { "angemeldet als $it" })
        }
        val z = O.schrittZeile(this, "Nextcloud verbinden")
        schritte.addView(z)
        zeilen.add(z)
        O.schrittStand(z, O.LAEUFT, null)
        val app = applicationContext
        Thread {
            val fehler = try {
                NextcloudZugang.holenUndSpeichern(app)
                null
            } catch (e: NextcloudZugang.Fehler) {
                e.message ?: "nicht möglich"
            } catch (e: Exception) {
                Log.w(TAG, "Nextcloud: ${e.javaClass.simpleName}")
                "keine Verbindung"
            }
            ui.post {
                if (isFinishing) return@post
                if (fehler == null) {
                    O.schrittStand(z, O.OK, null)
                    ui.postDelayed({ fertigSchliessen() }, 600)
                } else {
                    // Die App ist angemeldet; die Nextcloud geht spaeter von Hand
                    O.schrittStand(z, O.FEHLER, "$fehler – später unter Einstellungen → Rosenweg-Nextcloud")
                    ui.postDelayed({ fertigSchliessen() }, 3500)
                }
            }
        }.start()
    }

    // ─── Karte ──────────────────────────────────────────────────────────────

    private fun nfc(): NfcAdapter? = NfcAdapter.getDefaultAdapter(this)

    private fun leserEin() {
        if (kameraAn) return
        val n = nfc() ?: return
        if (!n.isEnabled) return
        try {
            n.enableReaderMode(this, { tag -> karte(Kartenleser.uid(tag)) }, Kartenleser.FLAGS, null)
        } catch (e: Exception) {
            Log.w(TAG, "Kartenleser: ${e.javaClass.simpleName}")
        }
    }

    private fun leserAus() {
        try { nfc()?.disableReaderMode(this) } catch (e: Exception) { }
    }

    /** Aus dem Leser-Thread. */
    private fun karte(uid: String?) {
        if (uid == null || !laeuft || prueft || !entpreller.neu(uid)) return
        prueft = true
        ui.post { ablaufZeigen() }
        Thread {
            try {
                KartenSitzung.anmelden(applicationContext, uid)
                ui.post { angemeldet() }
            } catch (e: KartenSitzung.Fehler) {
                ui.post { fehler(e.message ?: "Anmelden fehlgeschlagen") }
            } catch (e: Exception) {
                Log.w(TAG, "Karte: ${e.javaClass.simpleName}")
                ui.post { fehler("Keine Verbindung zum Rosenweg") }
            }
        }.start()
    }

    /** Karte oder QR eingeloest: Schritte gruen, dann weiter (Nextcloud) bzw. schliessen. */
    private fun angemeldet() {
        O.schrittStand(zeilen[0], O.OK, null)
        O.schrittStand(zeilen[1], O.OK, KartenSitzung.name(this)?.takeIf { it.isNotEmpty() }?.let { "angemeldet als $it" })
        ui.postDelayed({ prueft = false; if (!isFinishing) fertig() }, 800)
    }

    // ─── QR-Code ────────────────────────────────────────────────────────────

    private fun kameraDa() = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** "QR-Code scannen": Kamera an, Kartenleser aus; nach 60 s wieder zurueck. */
    private fun kameraOeffnen() {
        leserAus()
        kameraAn = true
        grundzustand()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Erst jetzt fragen (nicht beim Start der App)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), RECHT_KAMERA)
            return
        }
        kameraStarten()
    }

    private fun kameraStarten() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val s = CameraSettings().apply {
            isAutoFocusEnabled = true
            isContinuousFocusEnabled = true
        }
        kamera.pause()
        kamera.cameraSettings = s
        kamera.resume()
        ui.removeCallbacks(kameraZeitAus)
        ui.postDelayed(kameraZeitAus, KAMERA_MS)
    }

    private fun kameraStoppen() {
        ui.removeCallbacks(kameraZeitAus)
        if (::kamera.isInitialized) kamera.pause()
        if (!kameraAn) return
        kameraAn = false
        if (!prueft) grundzustand()
        if (laeuft && !prueft) leserEin()
    }

    override fun onRequestPermissionsResult(code: Int, rechte: Array<out String>, ergebnis: IntArray) {
        super.onRequestPermissionsResult(code, rechte, ergebnis)
        if (code != RECHT_KAMERA) return
        if (ergebnis.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (laeuft) kameraStarten()   // sonst startet onResume die Kamera
        } else {
            kameraStoppen()
            meldung("Ohne Zugriff auf die Kamera geht der QR-Code nicht. Karte oder Rosenweg-Konto gehen weiter.")
        }
    }

    private fun meldung(text: String?) {
        meldung.text = text.orEmpty()
        meldung.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private val gescannt = BarcodeCallback { r ->
        val inhalt = r.text?.trim() ?: return@BarcodeCallback
        if (!kameraAn || prueft) return@BarcodeCallback
        if (!inhalt.startsWith(KartenSitzung.QR_PRAEFIX)) {
            meldung(
                if (inhalt.startsWith(KartenSitzung.QR_PRAEFIX_TELEFON)) {
                    "Das ist der QR für geteilte Telefone. Wähle auf $QR_SEITE „App auf eigenem Handy“."
                } else {
                    "Das ist kein Rosenweg-Anmelde-QR. Öffne $QR_SEITE und wähle „App auf eigenem Handy“."
                }
            )
            return@BarcodeCallback
        }
        // Derselbe QR wird pro Sekunde mehrfach erkannt - nur einmal einloesen
        val jetzt = SystemClock.elapsedRealtime()
        if (inhalt == zuletztGescannt && jetzt - zuletztGescanntUm < 5000) return@BarcodeCallback
        zuletztGescannt = inhalt
        zuletztGescanntUm = jetzt
        prueft = true
        kameraStoppen()
        ablaufZeigen(listOf("QR-Code prüfen", "Anmeldung für $appName"), "QR-Code erkannt")
        Thread {
            try {
                KartenSitzung.anmeldenMitQr(applicationContext, inhalt)
                ui.post { angemeldet() }
            } catch (e: KartenSitzung.Fehler) {
                ui.post { fehler(e.message ?: "Anmelden fehlgeschlagen") }
            } catch (e: Exception) {
                Log.w(TAG, "QR: ${e.javaClass.simpleName}")
                ui.post { fehler("Keine Verbindung zum Rosenweg") }
            }
        }.start()
    }

    // ─── Zustaende ──────────────────────────────────────────────────────────

    /** Wie der Sperrbildschirm: Logo gross, Karte, Konto- und QR-Knopf; beim Scannen das Kamerabild. */
    private fun grundzustand() {
        kopf.text = "$appName · zum Benutzen anmelden"
        kopf.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        kopf.setTextColor(Color.parseColor(O.HELLBLAU))
        schrittRahmen.visibility = View.GONE
        obenPlatz.visibility = View.VISIBLE
        untenPlatz.visibility = View.VISIBLE
        logoGroesse(230)
        kontoKnopf.visibility = View.VISIBLE
        meldung.visibility = View.GONE
        val qr = kameraDa()
        qrKnopf.visibility = if (qr) View.VISIBLE else View.GONE
        qrKnopf.text = if (kameraAn) O.KAMERA_AUS else O.QR_KNOPF
        hinweis.visibility = View.VISIBLE
        if (kameraAn) {
            // Wie der Sperrbildschirm beim Scannen: Kamerabild statt Logo und Karte
            kameraRahmen.visibility = View.VISIBLE
            logo.visibility = View.GONE
            kartenHinweis.visibility = View.GONE
            nfcKnopf.visibility = View.GONE
            hinweis.text = "Öffne $QR_SEITE\nauf einem anderen Gerät (z.B. am Computer),\nwähle „App auf eigenem Handy“\nund halte die Kamera auf den QR-Code."
            return
        }
        kameraRahmen.visibility = View.GONE
        logo.visibility = View.VISIBLE
        val oderQr = if (qr) "„QR-Code scannen“ und den Anmelde-QR\nvon $QR_SEITE scannen,\noder " else ""
        val n = nfc()
        when {
            n == null -> {
                kartenHinweis.visibility = View.GONE
                nfcKnopf.visibility = View.GONE
                hinweis.text = "Dieses Handy kann keine Karten lesen.\n" +
                    (if (qr) "Melde dich unten mit dem Rosenweg-Konto oder per QR-Code an." else "Melde dich unten mit dem Rosenweg-Konto an.")
            }
            !n.isEnabled -> {
                kartenHinweis.visibility = View.VISIBLE
                kartenHinweis.text = O.NFC_AUS
                nfcKnopf.visibility = View.VISIBLE
                hinweis.text = "Schalte NFC ein, um dich mit der Rosenweg-Karte anzumelden,\n" +
                    (if (qr) "oder melde dich unten mit dem Rosenweg-Konto oder per QR-Code an." else "oder melde dich unten mit dem Rosenweg-Konto an.")
            }
            else -> {
                kartenHinweis.visibility = View.VISIBLE
                kartenHinweis.text = O.KARTE_HINWEIS
                nfcKnopf.visibility = View.GONE
                hinweis.text = "Oder: ${oderQr}unten mit dem Rosenweg-Konto anmelden."
            }
        }
    }

    /** Karte erkannt: Schritte wie beim Anmelden am geteilten Telefon. */
    private fun ablaufZeigen(
        titel: List<String> = listOf("Rosenweg-Karte prüfen", "Anmeldung für $appName"),
        erkannt: String = "Karte erkannt",
    ) {
        kopf.text = O.ANMELDEN
        kopf.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        kopf.setTextColor(Color.WHITE)
        for (v in listOf(kartenHinweis, hinweis, meldung, nfcKnopf, kontoKnopf, qrKnopf, kameraRahmen, obenPlatz, untenPlatz)) {
            v.visibility = View.GONE
        }
        logo.visibility = View.VISIBLE
        logoGroesse(110)
        schritte.removeAllViews()
        zeilen.clear()
        for (t in titel) {
            val z = O.schrittZeile(this, t)
            schritte.addView(z)
            zeilen.add(z)
        }
        schrittRahmen.visibility = View.VISIBLE
        O.schrittStand(zeilen[0], O.LAEUFT, if (holtNextcloud) null else erkannt)
    }

    /** Wie der Sperrbildschirm: Schritt rot mit Ursache, nach 8 s zurueck. */
    private fun fehler(text: String) {
        zeilen.getOrNull(0)?.let { O.schrittStand(it, O.FEHLER, "$text – ${O.NOCHMALS}") }
        ui.postDelayed({
            prueft = false
            if (!isFinishing) grundzustand()
            if (laeuft) leserEin()
        }, 8000)
    }

    // ─── Oberflaeche (Bausteine aus dem Modul anmeldeschirm) ────────────────

    private fun dp(v: Number) = O.dp(this, v.toFloat())

    private fun text(sp: Float, farbe: String, fett: Boolean) = O.text(this, sp, Color.parseColor(farbe), fett)

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
            setBackgroundColor(Color.parseColor(O.BLAU))
            setPadding(dp(24), dp(40), dp(24), dp(24))
            fitsSystemWindows = true
        }
        kopf = text(15f, O.HELLBLAU, false).apply { setPadding(0, dp(4), 0, dp(18)) }
        l.addView(kopf)

        // Kamerabild beim QR-Scannen (wie der Sperrbildschirm: oben, 280 dp, runder Rahmen)
        kameraRahmen = O.kameraRahmen(this).apply { visibility = View.GONE }
        kamera = BarcodeView(this).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            decodeContinuous(gescannt)
        }
        kameraRahmen.addView(kamera, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        l.addView(kameraRahmen, LinearLayout.LayoutParams(dp(280), dp(280)))

        obenPlatz = View(this)
        l.addView(obenPlatz, LinearLayout.LayoutParams(1, 0, 1f))
        logo = O.logo(this)
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
        hinweis = text(14f, O.HELLBLAU, false).apply { setPadding(0, dp(18), 0, 0) }
        l.addView(hinweis)
        meldung = text(15f, O.ROT, false).apply {
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        l.addView(meldung)

        untenPlatz = View(this)
        l.addView(untenPlatz, LinearLayout.LayoutParams(1, 0, 1f))

        nfcKnopf = O.knopf(this, O.NFC_EINSCHALTEN, O.KNOPF_DUNKEL).apply {
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        }
        l.addView(nfcKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })

        kontoKnopf = O.knopf(this, O.KONTO_KNOPF, O.KNOPF).apply {
            setOnClickListener { RosenwegAnmeldung.starten(this@RosenwegAnmeldeSchirm) }
        }
        l.addView(kontoKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

        // Wie am Sperrbildschirm unter dem Konto-Knopf, etwas dunkler und niedriger
        qrKnopf = O.knopf(this, O.QR_KNOPF, O.KNOPF_DUNKEL).apply {
            setOnClickListener { if (kameraAn) kameraStoppen() else kameraOeffnen() }
        }
        l.addView(qrKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })

        val fuss = text(12f, O.HELLBLAU, false).apply {
            text = "Die Anmeldung gilt nur für $appName."
            setPadding(0, dp(8), 0, 0)
        }
        l.addView(fuss)
        return l
    }
}
