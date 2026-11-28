package org.fossify.commons.helpers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.commons.R
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.RosenwegFarbRegel.Gruppe

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

    /** Text-Ressource der App (ohne Abhaengigkeit auf ihre R-Klasse); fehlt sie: leer. */
    private fun ressource(c: Context, name: String): String {
        val id = c.resources.getIdentifier(name, "string", c.packageName)
        if (id == 0) return ""
        return try {
            c.getString(id)
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "Ressource $name fehlt", e)
            ""
        }
    }

    /** Mindestfarbe der App (Ressource rosenweg_gruppe; ohne Angabe rot). */
    fun standard(c: Context): Gruppe = Gruppe.ausName(ressource(c, "rosenweg_gruppe")) ?: Gruppe.BEWOHNER

    /** Hoehere Farben der App (Ressource rosenweg_farb_gruppen, mit Komma). Leer = nie wechseln. */
    fun farbGruppen(c: Context): Set<Gruppe> =
        ressource(c, "rosenweg_farb_gruppen").split(',').mapNotNull { Gruppe.ausName(it) }.toSet()

    /** Zuletzt gesetzte Farbe (beim Start sofort richtig), sonst die Mindestfarbe. */
    fun aktuell(c: Context): Gruppe =
        Gruppe.ausName(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SCHLUESSEL, null)) ?: standard(c)

    /** Person angemeldet: Farbe aus ihren Gruppen (und Seitenrechten). Darf im Hintergrund laufen. */
    fun setzen(c: Context, gruppen: Collection<String>, rechte: Map<String, String> = emptyMap()) {
        val farben = farbGruppen(c)
        if (farben.isEmpty()) return
        val farbRechte = ressource(c, "rosenweg_farb_rechte").split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        anwenden(c, RosenwegFarbRegel.berechnen(standard(c), farben, farbRechte, gruppen, rechte))
    }

    /** Abgemeldet bzw. niemand am geteilten Telefon: zurueck auf die Mindestfarbe. */
    fun abgemeldet(c: Context) {
        if (farbGruppen(c).isNotEmpty()) anwenden(c, standard(c))
    }

    @Synchronized
    private fun anwenden(c: Context, neu: Gruppe) {
        val vorher = aktuell(c)
        // auch ohne Wechsel, wenn das Startsymbol nicht passt (z.B. nach "Daten loeschen")
        if (neu == vorher && an(c, neu.alias)) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SCHLUESSEL, neu.name).apply()
        Log.i(TAG, "Gruppenfarbe ${vorher.name} -> ${neu.name}")
        akzent(c, vorher, neu)
        symbol(c, neu)
    }

    /** Ist die Alias eingeschaltet (ausdruecklich oder laut Manifest)? Fehlt sie: false. */
    private fun an(c: Context, alias: String): Boolean {
        val pm = c.packageManager
        val cn = komponente(c, alias)
        return when (pm.getComponentEnabledSetting(cn)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> try {
                pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d(TAG, "keine Alias $alias", e)
                false
            }
            else -> false
        }
    }

    /** Erst die neue Alias an, dann alle anderen aus (so gibt es immer ein Startsymbol). */
    private fun symbol(c: Context, g: Gruppe) {
        val pm = c.packageManager
        val schalten = { alias: String, zustand: Int ->
            runCatching { pm.setComponentEnabledSetting(komponente(c, alias), zustand, PackageManager.DONT_KILL_APP) }
                .onFailure { Log.w(TAG, "Alias $alias nicht umgeschaltet", it) }
        }
        schalten(g.alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        appIconColorStrings.filter { it != g.alias && an(c, it) }
            .forEach { schalten(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
    }

    /** Haupt- und Akzentfarbe, solange die Person keine eigene gewaehlt hat. */
    private fun akzent(c: Context, vorher: Gruppe, neu: Gruppe) {
        val cfg = c.baseConfig
        val standardPrimaer = ContextCompat.getColor(c, R.color.default_primary_color)
        val standardAkzent = ContextCompat.getColor(c, R.color.default_accent_color)
        if (cfg.primaryColor == vorher.farbe || cfg.primaryColor == standardPrimaer) cfg.primaryColor = neu.farbe
        if (cfg.accentColor == vorher.farbe || cfg.accentColor == standardAkzent) cfg.accentColor = neu.farbe
    }
}

/** Fossify-Alias `activities.SplashActivity<alias>` dieser App. */
private fun komponente(c: Context, alias: String) =
    ComponentName(c.packageName, "${c.packageName.removeSuffix(".debug")}.activities.SplashActivity$alias")
