package ch.rosenweg4303.anmeldeschirm

import android.content.Context

/**
 * Farben des Anmeldeschirms je Gruppe der App (Stefan 27.11.2026): der Schirm erscheint in der
 * Farbe des Logo-Grundes (rosenweg-basis werkzeug/logo/baukasten.py --gruppe):
 *
 *  - bewohner   Rosenweg-Rot  #D62828
 *  - verwaltung Rosenweg-Blau #1E3A8A (Ausschuss, Hausverwaltung; bisher die Farbe fuer alle)
 *  - technik    Dunkelgruen   #2E6B4F
 *
 * Alles andere (Logo, Texte, Knoepfe, Aufbau) bleibt gleich. Blau sind genau die bisherigen
 * Werte von [AnmeldeOberflaeche]. Fuer Rot und Gruen sind die hellen Texte und Meldungen so
 * gewaehlt, dass sie auf dem Grund mindestens 4.5:1 erreichen (WCAG AA), ebenso weisse Schrift
 * auf beiden Knoepfen; die Knoepfe sind dort dunkler als der Grund (heller ginge mit weisser
 * Schrift nicht) und behalten den weissen Rand. Gleiche Werte in rosenweg_basis (AnmeldePalette).
 *
 * Woher die Gruppe kommt: [fuer] liest die Ressource `rosenweg_gruppe` der App (Standard leer =
 * Blau, also auch der Sperrbildschirm von Rosenweg Geraet, der nichts setzt).
 */
class AnmeldeFarben private constructor(
    /** Name der Gruppe: bewohner, verwaltung, technik. */
    @JvmField val gruppe: String,
    @JvmField val grund: Int,
    /** Helle Schrift (Kopf, Hinweise, Fuss, offene Schritte). */
    @JvmField val hell: Int,
    /** Hauptknopf ("Mit Rosenweg-Konto anmelden"). */
    @JvmField val knopf: Int,
    /** Zweitknopf ("QR-Code scannen", "NFC einschalten"). */
    @JvmField val knopfDunkel: Int,
    /** Rand des Kamerabilds. */
    @JvmField val rahmen: Int,
    @JvmField val fehler: Int,
    @JvmField val ok: Int,
    @JvmField val warnung: Int,
) {
    companion object {
        const val BEWOHNER = "bewohner"
        const val VERWALTUNG = "verwaltung"
        const val TECHNIK = "technik"

        /** Ausschuss/Verwaltung und Standard: die bisherigen Farben. */
        @JvmField
        val BLAU = AnmeldeFarben(
            VERWALTUNG,
            grund = 0xFF1E3A8A.toInt(), hell = 0xFFBFDBFE.toInt(),
            knopf = 0xFF2563EB.toInt(), knopfDunkel = 0xFF1E40AF.toInt(), rahmen = 0xFF2563EB.toInt(),
            fehler = 0xFFFCA5A5.toInt(), ok = 0xFFBBF7D0.toInt(), warnung = 0xFFFDE68A.toInt(),
        )

        /** Bewohner. */
        @JvmField
        val ROT = AnmeldeFarben(
            BEWOHNER,
            grund = 0xFFD62828.toInt(), hell = 0xFFFFF1F1.toInt(),
            knopf = 0xFFA51D1D.toInt(), knopfDunkel = 0xFF7F1616.toInt(), rahmen = 0xFFFFF1F1.toInt(),
            fehler = 0xFFFFF7CC.toInt(), ok = 0xFFEBFFF1.toInt(), warnung = 0xFFFFF7CC.toInt(),
        )

        /** Technik. */
        @JvmField
        val GRUEN = AnmeldeFarben(
            TECHNIK,
            grund = 0xFF2E6B4F.toInt(), hell = 0xFFD7F2E3.toInt(),
            knopf = 0xFF1F5A3F.toInt(), knopfDunkel = 0xFF173F2D.toInt(), rahmen = 0xFFD7F2E3.toInt(),
            fehler = 0xFFFFE1E1.toInt(), ok = 0xFFBBF7D0.toInt(), warnung = 0xFFFDE68A.toInt(),
        )

        /** Gruppe als Name (bewohner/verwaltung/technik, auch rot/blau/gruen); sonst Blau. */
        @JvmStatic
        fun gruppe(name: String?): AnmeldeFarben = when (name?.trim()?.lowercase()) {
            BEWOHNER, "rot" -> ROT
            TECHNIK, "gruen", "grün" -> GRUEN
            else -> BLAU
        }

        /** Farben dieser App: Ressource `rosenweg_gruppe` (leer/fehlend = Blau). */
        @JvmStatic
        fun fuer(c: Context): AnmeldeFarben = gruppe(
            try { c.getString(R.string.rosenweg_gruppe) } catch (e: Exception) { null },
        )
    }
}
