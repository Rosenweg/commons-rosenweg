package ch.rosenweg4303.updater

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Aussehen der Seite "Rosenweg-Updates" (0.1.8, Stefan 27.11.2026: "sieht zu schlecht aus"):
 * Kopf mit Logo in der Gruppenfarbe der App, je App eine Zeile mit Symbol, Name, "alt -> neu"
 * und eigener Statuszeile (wartet / laedt mit Balken / bestaetigen / installiert / Fehler), ein
 * grosser Hauptknopf, Hinweiskarte (z.B. fuer das Recht "Unbekannte Apps installieren"). Hell und
 * dunkel nach System. Nur Views, keine Logik - die steckt in [UpdateActivity].
 */
class UpdateAnsicht(private val c: Context, private val dunkel: Boolean = istDunkel(c), gruppeFest: Int? = null, logo: android.graphics.drawable.Drawable? = null) {

    /** Farben wie rosenweg_basis (Material 3 aus #8B1E3F), Kopf in der Gruppenfarbe. */
    private val grund = if (dunkel) 0xFF1A1112.toInt() else 0xFFFFF8F7.toInt()
    private val karte = if (dunkel) 0xFF271D1E.toInt() else 0xFFFFFFFF.toInt()
    private val rahmen = if (dunkel) 0xFF524345.toInt() else 0xFFE7D6D7.toInt()
    private val schrift = if (dunkel) 0xFFF0DEDF.toInt() else 0xFF22191A.toInt()
    private val leise = if (dunkel) 0xFFD6C2C3.toInt() else 0xFF534344.toInt()
    val gruppe: Int = gruppeFest ?: gruppenfarbe(c)
    /** Gruppenfarbe als Schrift auf dem Grund: im Dunkeln aufgehellt (lesbar). */
    private val gruppeSchrift: Int = if (dunkel) aufhellen(gruppe) else gruppe
    private val logoBild = logo
    private val gut = if (dunkel) 0xFF81C784.toInt() else 0xFF2E7D32.toInt()
    private val warnung = if (dunkel) 0xFFFFB74D.toInt() else 0xFFB26A00.toInt()
    private val fehler = if (dunkel) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()

    enum class Zustand { WARTET, LAEDT, BESTAETIGEN, INSTALLIERT, FEHLER, AKTUELL }

    /** Eine Zeile je App. */
    inner class Zeile(val paket: String, name: String, alt: String, neu: String, symbol: Drawable?) {
        val view: LinearLayout
        private val status: TextView
        private val balken: ProgressBar

        init {
            status = TextView(c).apply { textSize = 14f }
            balken = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(gruppeSchrift)
            }
            view = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = flaeche(karte, rahmen, 16)
                addView(ImageView(c).apply {
                    setImageDrawable(symbol ?: c.applicationInfo.loadIcon(c.packageManager))
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(c).apply {
                        this.text = name; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
                        setTextColor(schrift); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(c).apply {
                        this.text = if (alt.isEmpty()) neu else "$alt  →  $neu"; textSize = 15f; setTextColor(leise)
                    })
                    addView(status)
                    addView(balken, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            setzen(Zustand.WARTET)
        }

        fun setzen(z: Zustand, meldung: String? = null, anteil: Float? = null) {
            val (t, farbe) = when (z) {
                Zustand.WARTET -> "Wartet" to leise
                Zustand.LAEDT -> (if (anteil != null) "Lädt … ${(anteil * 100).toInt()} %" else "Lädt …") to gruppeSchrift
                Zustand.BESTAETIGEN -> "Bitte im Android-Fenster bestätigen" to warnung
                Zustand.INSTALLIERT -> "✓ Installiert" to gut
                Zustand.FEHLER -> "✕ ${meldung ?: "Nicht installiert"}" to fehler
                Zustand.AKTUELL -> "✓ Aktuell" to gut
            }
            status.text = t; status.setTextColor(farbe)
            balken.visibility = if (z == Zustand.LAEDT) View.VISIBLE else View.GONE
            balken.isIndeterminate = anteil == null
            if (anteil != null) balken.progress = (anteil * 1000).toInt()
        }
    }

    val wurzel: LinearLayout
    val titel: TextView
    val untertitel: TextView
    private val liste: LinearLayout
    private val hinweis: LinearLayout
    private val hinweisText: TextView
    val hinweisKnopf: Button
    val knopf: Button
    val nebenKnopf: Button
    private val sucht: ProgressBar
    val zeilen = linkedMapOf<String, Zeile>()

    init {
        titel = TextView(c).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) }
        untertitel = TextView(c).apply { textSize = 15f; setTextColor(0xEEFFFFFF.toInt()) }
        val kopf = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(gruppe)
            setPadding(dp(20), dp(28), dp(20), dp(20))
            addView(ImageView(c).apply {
                setImageDrawable(logoBild ?: c.applicationInfo.loadIcon(c.packageManager))
                background = flaeche(0xFFFFFFFF.toInt(), 0, 14)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply {
                    text = "Rosenweg-Updates"; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); letterSpacing = 0.05f
                })
                addView(titel); addView(untertitel)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        hinweisText = TextView(c).apply { textSize = 15f; setTextColor(schrift) }
        hinweisKnopf = Button(c).apply {
            isAllCaps = false; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (dunkel) 0xFF2B1700.toInt() else 0xFFFFFFFF.toInt()); background = flaeche(warnung, 0, 24)
        }
        hinweis = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = flaeche(karte, warnung, 16)
            visibility = View.GONE
            addView(hinweisText)
            addView(hinweisKnopf, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { topMargin = dp(10) })
        }
        sucht = ProgressBar(c).apply { isIndeterminate = true; visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(gruppe) }
        liste = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        knopf = Button(c).apply {
            isAllCaps = false; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt()); background = flaeche(gruppe, 0, 28)
        }
        nebenKnopf = Button(c).apply {
            isAllCaps = false; textSize = 16f; setTextColor(gruppeSchrift); background = flaeche(0, gruppeSchrift, 28); visibility = View.GONE
        }
        val unten = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            setBackgroundColor(grund)
            addView(knopf, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
            addView(nebenKnopf, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        }
        val inhalt = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(hinweis, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addView(FrameLayout(c).apply { addView(sucht, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(liste)
        }
        wurzel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(grund)
            addView(kopf, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(c).apply { isFillViewport = true; addView(inhalt) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(unten, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    fun kopf(t: String, u: String) { titel.text = t; untertitel.text = u }

    fun sucht(ja: Boolean) { sucht.visibility = if (ja) View.VISIBLE else View.GONE }

    fun zeilenSetzen(l: List<Zeile>) {
        liste.removeAllViews(); zeilen.clear()
        l.forEach { z ->
            zeilen[z.paket] = z
            liste.addView(z.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    fun hinweis(t: String?, knopfText: String? = null, aktion: (() -> Unit)? = null) {
        hinweis.visibility = if (t == null) View.GONE else View.VISIBLE
        hinweisText.text = t.orEmpty()
        hinweisKnopf.visibility = if (knopfText == null) View.GONE else View.VISIBLE
        hinweisKnopf.text = knopfText.orEmpty()
        hinweisKnopf.setOnClickListener { aktion?.invoke() }
    }

    fun hauptknopf(t: String, aktiv: Boolean = true, aktion: (() -> Unit)? = null) {
        knopf.text = t; knopf.isEnabled = aktiv; knopf.alpha = if (aktiv) 1f else 0.5f
        knopf.setOnClickListener { aktion?.invoke() }
    }

    fun nebenknopf(t: String?, aktion: (() -> Unit)? = null) {
        nebenKnopf.visibility = if (t == null) View.GONE else View.VISIBLE
        nebenKnopf.text = t.orEmpty(); nebenKnopf.setOnClickListener { aktion?.invoke() }
    }

    private fun flaeche(farbe: Int, rand: Int, radius: Int) = GradientDrawable().apply {
        setColor(farbe); cornerRadius = dp(radius).toFloat()
        if (rand != 0) setStroke(dp(1), rand)
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    companion object {
        /** Farbe zur Haelfte Richtung Weiss. */
        fun aufhellen(f: Int): Int {
            fun k(x: Int) = x + (255 - x) / 2
            return (0xFF shl 24) or (k((f shr 16) and 0xFF) shl 16) or (k((f shr 8) and 0xFF) shl 8) or k(f and 0xFF)
        }

        fun istDunkel(c: Context) =
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        /** Gruppenfarbe der App wie ihr Logo (res/values rosenweg_gruppe, siehe anmeldeschirm). */
        fun gruppenfarbe(c: Context): Int {
            val id = c.resources.getIdentifier("rosenweg_gruppe", "string", c.packageName)
            val g = if (id != 0) c.getString(id).trim().lowercase() else ""
            return when (g) {
                "bewohner" -> 0xFFD62828.toInt()
                "technik" -> 0xFF2E6B4F.toInt()
                "verwaltung", "ausschuss" -> 0xFF1E3A8A.toInt()
                else -> 0xFF8B1E3F.toInt()   // Rosenweg-Grundton
            }
        }
    }
}
