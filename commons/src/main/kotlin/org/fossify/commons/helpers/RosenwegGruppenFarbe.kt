package org.fossify.commons.helpers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.commons.R
import org.fossify.commons.extensions.baseConfig

/**
 * Gruppenfarbe nach der angemeldeten Person (Entscheid Stefan 28.11.2026, wie rosenweg-basis
 * docs/gruppenfarbe.md): Technik und Praesident gruen, Ausschuss und Verwaltung blau, sonst rot -
 * aber nur, soweit die App fuer diese Gruppe etwas freischaltet.
 *
 * Die App sagt das in Ressourcen (ohne sie passiert hier nichts, Fossify-Apps bleiben wie sie sind):
 * ```xml
 * <string name="rosenweg_gruppe" translatable="false">bewohner</string>              <!-- Mindestfarbe -->
 * <string name="rosenweg_farb_gruppen" translatable="false">verwaltung,technik</string>
 * <string name="rosenweg_farb_rechte" translatable="false"></string>                 <!-- optional -->
 * ```
 * und meldet die Person: [setzen] nach der Anmeldung (Gruppen aus dem Token bzw. /api/auth/me,
 * dort auch die Seitenrechte), [abgemeldet] beim Abmelden. Auf geteilten Telefonen bei jedem
 * Personenwechsel neu [setzen].
 *
 * Was folgt:
 *  - **Startsymbol:** die vorhandenen Fossify-Aliasse `activities.SplashActivity.<Farbe>`:
 *    bewohner `.Green` (der bisherige Standard), verwaltung `.Blue`, technik `.Teal`; die Aliasse
 *    tragen im Manifest das Symbol der Gruppenfarbe (`@mipmap/ic_launcher_verwaltung` …, aus
 *    rosenweg-basis android-apps.sh). Umgeschaltet wird nur bei echtem Wechsel, erst die neue an,
 *    dann alle anderen aus (DONT_KILL_APP). Fossifys eigene Symbolwahl ist fuer Rosenweg-Apps aus
 *    ([Rosenweg.festesSymbol], checkAppIconColor/appLaunched). Achtung: Manche Launcher laden
 *    dabei kurz neu und entfernen eine Verknuepfung vom Startbildschirm.
 *  - **Akzent:** Haupt- und Akzentfarbe der App, solange die Person dort keine eigene Farbe
 *    gewaehlt hat (= sie steht noch auf dem Standard oder der zuletzt gesetzten Gruppenfarbe).
 *  - Die Farbe wird gemerkt ([aktuell]); der naechste Start zeigt sie sofort.
 * Der Anmeldeschirm (cloud/anmeldeschirm) bleibt in der Mindestfarbe: er erscheint nur ohne
 * Anmeldung, und dann gilt sie.
 */
object RosenwegGruppenFarbe {
    private const val TAG = "RosenwegGruppenFarbe"
    private const val PREFS = "rosenweg_gruppenfarbe"
    private const val SCHLUESSEL = "gruppe"

    enum class Gruppe(val farbe: Int, val alias: String) {
        BEWOHNER(0xFFD62828.toInt(), ".Green"),
        VERWALTUNG(0xFF1E3A8A.toInt(), ".Blue"),
        TECHNIK(0xFF2E6B4F.toInt(), ".Teal");

        fun max(andere: Gruppe) = if (andere.ordinal > ordinal) andere else this

        companion object {
            fun ausName(name: String?): Gruppe? = entries.firstOrNull { it.name.equals(name?.trim(), true) }
        }
    }

    private val ALLE_RECHTE = setOf("technik", "präsident", "praesident")
    private val AUSSCHUSS_VERWALTUNG = Regex("^(stweg\\d+|meg)-(ausschuss|verwaltung)$")

    fun istAusschussOderVerwaltung(gruppe: String): Boolean {
        val g = gruppe.lowercase()
        return g == "verwaltung" || g == "ausschuss" || AUSSCHUSS_VERWALTUNG.matches(g)
    }

    /**
     * Farbe fuer [gruppen] und Seitenrechte [rechte] (Seite -> read/write/none, wie /api/auth/me).
     * Rein, ohne Android - fuer Tests.
     */
    fun berechnen(
        standard: Gruppe,
        farbGruppen: Set<Gruppe>,
        farbRechte: Set<String>,
        gruppen: Collection<String>,
        rechte: Map<String, String> = emptyMap(),
    ): Gruppe {
        val alles = gruppen.any { it.lowercase() in ALLE_RECHTE }
        if (Gruppe.TECHNIK in farbGruppen && alles) return standard.max(Gruppe.TECHNIK)
        if (Gruppe.VERWALTUNG in farbGruppen) {
            val blau = if (farbRechte.isEmpty()) {
                gruppen.any(::istAusschussOderVerwaltung)
            } else {
                alles || farbRechte.any { rechte[it] == "read" || rechte[it] == "write" }
            }
            if (blau) return standard.max(Gruppe.VERWALTUNG)
        }
        return standard
    }

    private fun text(c: Context, name: String): String? {
        val id = c.resources.getIdentifier(name, "string", c.packageName)
        return if (id == 0) null else try { c.getString(id) } catch (e: Exception) { null }
    }

    /** Mindestfarbe der App (Ressource rosenweg_gruppe; ohne Angabe rot). */
    fun standard(c: Context): Gruppe = Gruppe.ausName(text(c, "rosenweg_gruppe")) ?: Gruppe.BEWOHNER

    /** Hoehere Farben der App (Ressource rosenweg_farb_gruppen, mit Komma). Leer = nie wechseln. */
    fun farbGruppen(c: Context): Set<Gruppe> =
        text(c, "rosenweg_farb_gruppen").orEmpty().split(',').mapNotNull { Gruppe.ausName(it) }.toSet()

    private fun farbRechte(c: Context): Set<String> =
        text(c, "rosenweg_farb_rechte").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Vergibt diese App ueberhaupt andere Farben als die Mindestfarbe? */
    fun wechselt(c: Context): Boolean {
        val s = standard(c)
        return farbGruppen(c).any { it.ordinal > s.ordinal }
    }

    /** Zuletzt gesetzte Farbe (beim Start sofort richtig), sonst die Mindestfarbe. */
    fun aktuell(c: Context): Gruppe =
        Gruppe.ausName(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SCHLUESSEL, null)) ?: standard(c)

    /** Farbwert von [aktuell], z.B. fuer eigene Ansichten. */
    fun farbe(c: Context): Int = aktuell(c).farbe

    /** Person angemeldet: Farbe aus ihren Gruppen (und Seitenrechten). Darf im Hintergrund laufen. */
    fun setzen(c: Context, gruppen: Collection<String>, rechte: Map<String, String> = emptyMap()) {
        if (!wechselt(c)) return
        anwenden(c, berechnen(standard(c), farbGruppen(c), farbRechte(c), gruppen, rechte))
    }

    /** Abgemeldet bzw. niemand am geteilten Telefon: zurueck auf die Mindestfarbe. */
    fun abgemeldet(c: Context) {
        if (!wechselt(c)) return
        anwenden(c, standard(c))
    }

    @Synchronized
    private fun anwenden(c: Context, neu: Gruppe) {
        val vorher = aktuell(c)
        if (neu == vorher && !symbolFalsch(c, neu)) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SCHLUESSEL, neu.name).apply()
        Log.i(TAG, "Gruppenfarbe ${vorher.name} -> ${neu.name}")
        akzent(c, vorher, neu)
        symbol(c, neu)
    }

    private fun klasse(c: Context, alias: String) =
        ComponentName(c.packageName, "${c.packageName.removeSuffix(".debug")}.activities.SplashActivity$alias")

    private fun an(c: Context, cn: ComponentName): Boolean {
        val pm = c.packageManager
        return when (pm.getComponentEnabledSetting(cn)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> try {
                pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            } catch (e: Exception) {
                false
            }
            else -> false
        }
    }

    /** Steht das Startsymbol nicht auf [g]? (z.B. nach einem Update, das die Aliasse neu setzte) */
    private fun symbolFalsch(c: Context, g: Gruppe): Boolean = try {
        !an(c, klasse(c, g.alias))
    } catch (e: Exception) {
        false
    }

    private fun symbol(c: Context, g: Gruppe) {
        val pm = c.packageManager
        try {
            val ziel = klasse(c, g.alias)
            pm.getActivityInfo(ziel, PackageManager.MATCH_DISABLED_COMPONENTS) // gibt es die Alias?
            if (!an(c, ziel)) {
                pm.setComponentEnabledSetting(ziel, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            for (a in appIconColorStrings) {
                if (a == g.alias) continue
                val cn = klasse(c, a)
                try {
                    if (an(c, cn)) {
                        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Startsymbol nicht umgeschaltet", e)
        }
    }

    private fun akzent(c: Context, vorher: Gruppe, neu: Gruppe) {
        val cfg = c.baseConfig
        val standardPrimaer = ContextCompat.getColor(c, R.color.default_primary_color)
        val standardAkzent = ContextCompat.getColor(c, R.color.default_accent_color)
        // Nur, solange die Person keine eigene Farbe gewaehlt hat
        if (cfg.primaryColor == vorher.farbe || cfg.primaryColor == standardPrimaer) cfg.primaryColor = neu.farbe
        if (cfg.accentColor == vorher.farbe || cfg.accentColor == standardAkzent) cfg.accentColor = neu.farbe
    }
}
