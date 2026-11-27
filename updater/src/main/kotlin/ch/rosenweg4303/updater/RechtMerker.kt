package ch.rosenweg4303.updater

import android.content.Context

/**
 * "Erlauben" fuer "Apps aus dieser Quelle installieren" wurde gedrueckt (0.1.8). Dauerhaft, weil
 * Android/Samsung beim Umschalten dieses Rechts den Prozess der App beendet (Befund A37 0.1.8b).
 * Gilt hoechstens 30 Minuten, damit ein vergessener Versuch die App nicht spaeter ueberrascht.
 */
internal object RechtMerker {
    private const val DATEI = "rosenweg_updater"
    private const val SEIT = "recht_seit"
    private const val DANACH = "recht_danach_installieren"
    private const val GUELTIG_MS = 30 * 60 * 1000L

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    fun merken(c: Context, danachInstallieren: Boolean) {
        p(c).edit().putLong(SEIT, System.currentTimeMillis()).putBoolean(DANACH, danachInstallieren).apply()
    }

    fun wartet(c: Context): Boolean {
        val seit = p(c).getLong(SEIT, 0)
        return seit > 0 && System.currentTimeMillis() - seit < GUELTIG_MS
    }

    fun danachInstallieren(c: Context) = p(c).getBoolean(DANACH, false)

    fun vergessen(c: Context) { p(c).edit().remove(SEIT).remove(DANACH).apply() }
}
