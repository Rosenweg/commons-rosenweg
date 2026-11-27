package org.fossify.commons.helpers

/**
 * Rosenweg-Anpassung von Fossify Commons.
 *
 * Die Rosenweg-Apps sind Forks der Fossify-Apps mit eigenem Paketnamen
 * (ch.rosenweg4303.<app>). Damit sie auch neben den Original-Fossify-Apps auf
 * privaten Handys laufen, ersetzen diese Helfer die festen "org.fossify."-
 * Pruefungen und Paketnamen in Commons. Alle Rosenweg-Aenderungen in Commons
 * gehen ueber diese Datei — so bleibt der Unterschied zum Original klein.
 */
object Rosenweg {
    const val PRAEFIX = "ch.rosenweg4303."

    /** Gehoert das Paket zur Rosenweg- oder Fossify-Familie? (".debug" wird ignoriert) */
    fun istUnsereApp(paket: String?): Boolean {
        val p = paket?.removeSuffix(".debug") ?: return false
        return p.startsWith(PRAEFIX, true) || p.startsWith("org.fossify.", true)
    }

    /**
     * Hat die App ein festes Rosenweg-Symbol (Paket ch.rosenweg4303.*)? Dann gibt es keine
     * Auswahl der Symbolfarbe: alle 18 Fossify-Farbvarianten (ic_launcher_red …) zeigen in den
     * Apps auf dasselbe Rosenweg-Logo, ein Wechsel wuerde nur den Aktivitaets-Alias umschalten,
     * und dabei kann das Symbol vom Startbildschirm verschwinden (Fossify-Warnung
     * app_icon_color_warning). Original-Fossify-Apps (org.fossify.*) behalten die Auswahl.
     * Apps mit Gruppenfarbe schalten die Aliasse selbst um: [RosenwegGruppenFarbe].
     */
    fun festesSymbol(paket: String?) = paket?.startsWith(PRAEFIX, true) == true

    /** Paketname einer Schwester-App, z.B. paket("contacts") -> ch.rosenweg4303.contacts */
    fun paket(app: String, debug: Boolean = false) = PRAEFIX + app + if (debug) ".debug" else ""

    /** Ist das Paket die angegebene Schwester-App (Rosenweg- oder Fossify-Variante)? */
    fun istApp(paket: String?, app: String): Boolean {
        val p = paket?.removeSuffix(".debug") ?: return false
        return p == PRAEFIX + app || p == "org.fossify.$app"
    }

    /** Kurzname ohne Praefix, z.B. fuer Dateinamen. */
    fun kurzname(paket: String) =
        paket.removeSuffix(".debug").removePrefix(PRAEFIX).removePrefix("org.fossify.")
}
