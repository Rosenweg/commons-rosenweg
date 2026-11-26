package ch.rosenweg4303.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/**
 * Hinweis beim ersten Start einer App, die in die Nextcloud hochlaedt
 * (Entscheid Stefan 24.11.2026): einmal fragen, Ablehnen ist erlaubt.
 *
 * - "Anmelden" oeffnet [AnmeldenActivity].
 * - "Nein, danke" wird gemerkt — die App fragt nie wieder. Anmelden geht
 *   weiterhin ueber die Einstellungen der App.
 * - Zurueck/Wegtippen zaehlt nicht als Antwort: beim naechsten Start wieder.
 *
 * Aufruf in onCreate der Haupt-Activity:
 *
 *     Hinweis.beimErstenStart(this, "Deine Aufnahmen")
 */
object Hinweis {
    private const val PREFS = "rosenweg_cloud_hinweis"
    private const val ABGELEHNT = "abgelehnt"

    /** @param was z.B. "Deine Aufnahmen", "Deine Fotos" — Subjekt des Hinweistexts. */
    fun beimErstenStart(activity: Activity, was: String) {
        if (KontoSpeicher(activity).laden() != null) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ABGELEHNT, false)) return
        if (activity.isFinishing) return

        AlertDialog.Builder(activity)
            .setTitle("Rosenweg-Nextcloud")
            .setMessage(
                "$was können automatisch in deinen persönlichen Ordner der Rosenweg-Nextcloud " +
                    "(${Hochladen.PERSOENLICH}) hochgeladen werden. Dort siehst du sie auch am " +
                    "Computer, und auf dem Telefon geht nichts verloren.\n\n" +
                    "Ohne Anmeldung bleibt alles nur auf diesem Telefon. Du kannst dich später " +
                    "in den Einstellungen der App anmelden."
            )
            .setPositiveButton("Anmelden") { _, _ ->
                activity.startActivity(Intent(activity, AnmeldenActivity::class.java))
            }
            .setNegativeButton("Nein, danke") { _, _ ->
                prefs.edit().putBoolean(ABGELEHNT, true).apply()
            }
            .show()
    }

    /** Hat die Person den Hinweis abgelehnt? (z.B. fuer einen Satz in den Einstellungen) */
    fun abgelehnt(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ABGELEHNT, false)
}
