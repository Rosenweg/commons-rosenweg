package ch.rosenweg4303.cloud

import ch.rosenweg4303.anmeldeschirm.AnmeldeFarben
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AnmeldeFarbenTest {
    private fun kanal(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun leucht(farbe: Int): Double =
        0.2126 * kanal(farbe shr 16 and 0xFF) + 0.7152 * kanal(farbe shr 8 and 0xFF) + 0.0722 * kanal(farbe and 0xFF)

    private fun kontrast(a: Int, b: Int): Double {
        val (h, d) = listOf(leucht(a), leucht(b)).sortedDescending()
        return (h + 0.05) / (d + 0.05)
    }

    private val weiss = 0xFFFFFFFF.toInt()

    @Test
    fun gruppeNachName() {
        assertSame(AnmeldeFarben.ROT, AnmeldeFarben.gruppe("bewohner"))
        assertSame(AnmeldeFarben.ROT, AnmeldeFarben.gruppe(" Bewohner "))
        assertSame(AnmeldeFarben.GRUEN, AnmeldeFarben.gruppe("technik"))
        assertSame(AnmeldeFarben.BLAU, AnmeldeFarben.gruppe("verwaltung"))
        // ohne Angabe oder unbekannt: blau wie bisher
        assertSame(AnmeldeFarben.BLAU, AnmeldeFarben.gruppe(null))
        assertSame(AnmeldeFarben.BLAU, AnmeldeFarben.gruppe(""))
        assertSame(AnmeldeFarben.BLAU, AnmeldeFarben.gruppe("ausschuss?"))
    }

    @Test
    fun grundWieDasLogo() {
        assertEquals(0xFFD62828.toInt(), AnmeldeFarben.ROT.grund)
        assertEquals(0xFF1E3A8A.toInt(), AnmeldeFarben.BLAU.grund)
        assertEquals(0xFF2E6B4F.toInt(), AnmeldeFarben.GRUEN.grund)
    }

    @Test
    fun lesbarNachWcagAA() {
        for (f in listOf(AnmeldeFarben.BLAU, AnmeldeFarben.ROT, AnmeldeFarben.GRUEN)) {
            for ((was, farbe) in listOf("weiss" to weiss, "hell" to f.hell, "fehler" to f.fehler, "ok" to f.ok, "warnung" to f.warnung)) {
                val k = kontrast(farbe, f.grund)
                assertTrue("${f.gruppe}: $was auf Grund nur ${"%.2f".format(k)}", k >= 4.5)
            }
            for ((was, farbe) in listOf("knopf" to f.knopf, "knopfDunkel" to f.knopfDunkel)) {
                val k = kontrast(weiss, farbe)
                assertTrue("${f.gruppe}: Schrift auf $was nur ${"%.2f".format(k)}", k >= 4.5)
            }
        }
    }
}
