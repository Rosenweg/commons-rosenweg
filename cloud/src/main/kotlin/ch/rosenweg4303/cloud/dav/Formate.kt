package ch.rosenweg4303.cloud.dav

import java.util.Locale

/**
 * Eine Zeile einer vCard/iCalendar-Datei: [gruppe].NAME;PARAM=WERT:inhalt. Mehrfache Parameter
 * (TYPE=home;TYPE=voice) werden mit "," zusammengelegt. [roh] ist die entfaltete Originalzeile -
 * so geben wir Eigenschaften, die wir nicht abbilden, unveraendert an den Server zurueck.
 */
internal data class Zeile(val name: String, val params: Map<String, String>, val wert: String, val roh: String = "", val gruppe: String = "") {
    /** Werte eines Parameters, klein geschrieben (TYPE=CELL,VOICE -> [cell, voice]). */
    fun liste(p: String): List<String> = params[p]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }.orEmpty()
}

internal object Zeilen {
    /** Entfaltet (RFC 5545/6350: Folgezeilen beginnen mit Leerzeichen/Tab) und zerlegt. */
    fun lesen(text: String): List<Zeile> {
        val roh = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val zeilen = mutableListOf<String>()
        for (z in roh) {
            if ((z.startsWith(" ") || z.startsWith("\t")) && zeilen.isNotEmpty()) zeilen[zeilen.size - 1] += z.substring(1)
            else if (z.isNotBlank()) zeilen.add(z)
        }
        return zeilen.mapNotNull { zerlegen(it) }
    }

    fun zerlegen(z: String): Zeile? {
        val dp = doppelpunkt(z)
        if (dp < 0) return null
        val kopf = teilenAusserhalbQuotes(z.substring(0, dp), ';')
        val voll = kopf[0]
        val name = voll.substringAfterLast('.').uppercase(Locale.ROOT)   // "item1.TEL" -> TEL
        val gruppe = if (voll.contains('.')) voll.substringBeforeLast('.') else ""
        val params = linkedMapOf<String, String>()
        for (p in kopf.drop(1)) {
            val k = p.substringBefore('=').uppercase(Locale.ROOT)
            val v = teilenAusserhalbQuotes(p.substringAfter('=', ""), ',').joinToString(",") { it.trim().trim('"') }
            params[k] = params[k]?.let { "$it,$v" } ?: v
        }
        return Zeile(name, params, z.substring(dp + 1), z, gruppe)
    }

    /** Erster Doppelpunkt ausserhalb von Anfuehrungszeichen (Parameterwerte duerfen ":" enthalten). */
    private fun doppelpunkt(z: String): Int {
        var inQ = false
        for (i in z.indices) {
            if (z[i] == '"') inQ = !inQ else if (z[i] == ':' && !inQ) return i
        }
        return -1
    }

    private fun teilenAusserhalbQuotes(s: String, t: Char): List<String> {
        val teile = mutableListOf<String>()
        val b = StringBuilder()
        var inQ = false
        for (ch in s) {
            if (ch == '"') inQ = !inQ
            if (ch == t && !inQ) { teile.add(b.toString()); b.setLength(0) } else b.append(ch)
        }
        teile.add(b.toString())
        return teile
    }

    /** Text-Wert zerlegen an nicht maskierten [t] (N, ADR, ORG mit ";", CATEGORIES mit ","), dann entmaskieren. */
    fun teilen(wert: String, t: Char): List<String> {
        val teile = mutableListOf<String>()
        val b = StringBuilder()
        var i = 0
        while (i < wert.length) {
            val ch = wert[i]
            if (ch == '\\' && i + 1 < wert.length) { b.append(ch).append(wert[i + 1]); i += 2; continue }
            if (ch == t) { teile.add(text(b.toString())); b.setLength(0) } else b.append(ch)
            i++
        }
        teile.add(text(b.toString()))
        return teile
    }

    fun text(wert: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < wert.length) {
            val ch = wert[i]
            if (ch == '\\' && i + 1 < wert.length) {
                when (val n = wert[i + 1]) {
                    'n', 'N' -> b.append('\n')
                    else -> b.append(n)
                }
                i += 2
            } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    fun maskieren(t: String): String = t.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /** Parameterwert, wenn noetig in Anfuehrungszeichen (enthaelt , ; :). */
    fun param(v: String): String {
        val s = v.replace("\"", "'").replace("\r", " ").replace("\n", " ")
        return if (s.any { it == ',' || it == ';' || it == ':' || it == ' ' } || s.isEmpty()) "\"$s\"" else s
    }

    /** Faltet auf hoechstens 75 Bytes (UTF-8, ohne Zeichen zu zerreissen) und schliesst mit CRLF. */
    fun falten(zeilen: List<String>): String = buildString {
        for (z in zeilen) {
            var bytes = 0
            var i = 0
            var erste = true
            while (i < z.length) {
                val cp = z.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                val grenze = if (erste) 75 else 74
                if (bytes + n > grenze) { append("\r\n "); bytes = 0; erste = false }
                appendCodePoint(cp)
                bytes += n
                i += Character.charCount(cp)
            }
            append("\r\n")
        }
    }
}
