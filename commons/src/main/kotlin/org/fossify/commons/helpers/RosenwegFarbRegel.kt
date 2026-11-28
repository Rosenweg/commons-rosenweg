package org.fossify.commons.helpers

/**
 * Regel der Gruppenfarbe (Entscheid Stefan 28.11.2026, wie rosenweg-basis docs/gruppenfarbe.md),
 * ohne Android: Technik und Praesident gruen, Ausschuss und Verwaltung blau, sonst die Mindestfarbe
 * der App - aber nur die Farben, die die App vergibt ([RosenwegGruppenFarbe]).
 */
object RosenwegFarbRegel {
    /** Farbe und Fossify-Alias (`activities.SplashActivity<alias>`) je Gruppe; Reihenfolge = Rang. */
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
     * Mit [farbRechte] zaehlt fuer Blau das Seitenrecht, sonst die Ausschuss-/Verwaltungsgruppe.
     */
    fun berechnen(
        standard: Gruppe,
        farbGruppen: Set<Gruppe>,
        farbRechte: Set<String>,
        gruppen: Collection<String>,
        rechte: Map<String, String> = emptyMap(),
    ): Gruppe {
        val alles = gruppen.any { it.lowercase() in ALLE_RECHTE }
        val blau = if (farbRechte.isEmpty()) {
            gruppen.any(::istAusschussOderVerwaltung)
        } else {
            alles || farbRechte.any { rechte[it] == "read" || rechte[it] == "write" }
        }
        return when {
            Gruppe.TECHNIK in farbGruppen && alles -> standard.max(Gruppe.TECHNIK)
            Gruppe.VERWALTUNG in farbGruppen && blau -> standard.max(Gruppe.VERWALTUNG)
            else -> standard
        }
    }
}
