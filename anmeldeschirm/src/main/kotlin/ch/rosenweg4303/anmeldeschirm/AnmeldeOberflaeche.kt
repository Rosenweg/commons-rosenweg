package ch.rosenweg4303.anmeldeschirm

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Aussehen und Texte des Rosenweg-Anmeldeschirms - EINMAL fuer alle (Stefan 26.11.2026: "so viel
 * wie moeglich wiederverwenden"). Stammt aus dem Sperrbildschirm der geteilten Telefone (Rosenweg
 * Geraet, SperrActivity, Stand 25.11.2026) und wird von dort wieder benutzt; ebenso vom
 * Anmeldeschirm der Apps auf privaten Handys (cloud: RosenwegAnmeldeSchirm, Flutter-Apps ueber
 * rosenweg_basis).
 *
 * Oberflaeche ohne Layout-Dateien (Rosenweg Geraet hat keine). Java: AnmeldeOberflaeche.knopf(...).
 */
object AnmeldeOberflaeche {
    // ─── Farben (wie der Ladebildschirm der Stationen) ──────────────────────
    const val BLAU = "#1E3A8A"
    const val HELLBLAU = "#BFDBFE"
    const val KNOPF = "#2563EB"
    const val KNOPF_DUNKEL = "#1E40AF"
    const val ROT = "#FCA5A5"
    const val GRUEN = "#BBF7D0"
    const val GELB = "#FDE68A"
    const val NOTRUF = "#DC2626"

    // ─── Texte ──────────────────────────────────────────────────────────────
    const val KARTE_HINWEIS = "Rosenweg-Karte\nhinten ans Telefon halten"
    const val KONTO_KNOPF = "Mit Rosenweg-Konto anmelden"
    const val ANMELDEN = "Anmelden"
    const val NFC_AUS = "NFC ist ausgeschaltet"
    const val NFC_EINSCHALTEN = "NFC einschalten"
    const val NOCHMALS = "bitte nochmals versuchen."
    /** Dritter Weg wie am Sperrbildschirm: QR von rosenweg4303.ch/smartphones scannen. */
    const val QR_KNOPF = "QR-Code scannen"
    const val KAMERA_AUS = "Kamera aus"

    // ─── Schritte (Einrichtungsbildschirm beim An-/Abmelden) ────────────────
    const val LAEUFT = 0
    const val OK = 1
    const val FEHLER = 2
    const val WARNUNG = 3

    @JvmStatic
    fun dp(c: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    @JvmStatic
    fun text(c: Context, sp: Float, farbe: Int, fett: Boolean): TextView = TextView(c).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(farbe)
        gravity = Gravity.CENTER
        if (fett) typeface = Typeface.DEFAULT_BOLD
    }

    /** Knopf mit runden Ecken und feinem weissem Rand (Stefan 25.11.2026). */
    @JvmStatic
    fun knopf(c: Context, beschriftung: String, farbe: String): Button = Button(c).apply {
        text = beschriftung
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.parseColor(farbe))
            cornerRadius = dp(c, 14f).toFloat()
            setStroke(maxOf(1, dp(c, 1.2f)), Color.WHITE)
        }
    }

    /**
     * Rahmen fuer das Kamerabild beim QR-Scannen: schwarz, runde Ecken, blauer Rand (wie der
     * Sperrbildschirm). Das Kamerabild kommt als Kind hinein (MATCH_PARENT).
     */
    @JvmStatic
    fun kameraRahmen(c: Context): FrameLayout = FrameLayout(c).apply {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(c, 18f).toFloat()
            setStroke(dp(c, 3f), Color.parseColor(KNOPF))
        }
        val r = dp(c, 3f)
        setPadding(r, r, r, r)
        clipToOutline = true
    }

    /** Rundes Rosenweg-Abzeichen (Turm, Weg, Rose). */
    @JvmStatic
    fun logo(c: Context): ImageView = ImageView(c).apply {
        setImageResource(R.drawable.rosenweg_anmelde_logo)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Rosenweg"
    }

    /** Zeile eines Schritts ("○  Titel"); der Titel steht im Tag. */
    @JvmStatic
    fun schrittZeile(c: Context, titel: String): TextView = text(c, 17f, Color.parseColor(HELLBLAU), false).apply {
        gravity = Gravity.START
        setPadding(0, dp(c, 5f), 0, dp(c, 5f))
        text = "○  $titel"
        tag = titel
    }

    /** Stand eines Schritts; bei FEHLER/WARNUNG mit kurzer Erklaerung darunter. */
    @JvmStatic
    fun schrittStand(z: TextView, stand: Int, erklaerung: String?) {
        val zeichen = when (stand) { LAEUFT -> "⏳"; OK -> "✓"; WARNUNG -> "⚠"; else -> "✗" }
        val farbe = when (stand) {
            LAEUFT -> Color.WHITE
            OK -> Color.parseColor(GRUEN)
            WARNUNG -> Color.parseColor(GELB)
            else -> Color.parseColor(ROT)
        }
        z.text = zeichen + "  " + z.tag + (if (erklaerung.isNullOrEmpty()) "" else "\n     $erklaerung")
        z.setTextColor(farbe)
        z.setTypeface(null, if (stand == LAEUFT) Typeface.BOLD else Typeface.NORMAL)
    }
}

/**
 * Rosenweg-Karte lesen (UniFi Access, dieselbe wie fuer die Tueren). Gelesen wird nur die
 * Seriennummer (UID, Tag.getId() als Hex, z.B. 04D4C227C22A81) - die nfc_id in UniFi Access.
 */
object Kartenleser {
    /** Reader-Modus: alle Kartentypen, NDEF nicht pruefen (nur die UID zaehlt). */
    const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    /** UID der Karte als Hex in Grossbuchstaben, null ohne Karte. */
    @JvmStatic
    fun uid(tag: Tag?): String? {
        val id = tag?.id ?: return null
        if (id.isEmpty()) return null
        return id.joinToString("") { "%02X".format(it) }
    }

    /** Der Leser meldet dieselbe Karte mehrfach: innert [ms] nur einmal durchlassen. */
    class Entpreller @JvmOverloads constructor(private val ms: Long = 4000) {
        private var zuletzt: String? = null
        private var um = 0L

        @Synchronized
        fun neu(uid: String): Boolean {
            val jetzt = SystemClock.elapsedRealtime()
            if (uid == zuletzt && jetzt - um < ms) return false
            zuletzt = uid
            um = jetzt
            return true
        }
    }
}
