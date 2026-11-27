package ch.rosenweg4303.cloud.dav

import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Telefonnummer, E-Mail-Adresse oder Webseite. [typen] klein geschrieben wie in der vCard
 * (cell, home, work, fax, pager, ...), [bezeichnung] eine eigene Bezeichnung (X-ABLabel).
 */
data class VEintrag(val typen: List<String>, val wert: String, val bezeichnung: String = "", val bevorzugt: Boolean = false) {
    /** Erster sprechender Typ (ohne pref/voice/internet), fuer einfache Abbildungen. */
    val typ: String get() = typen.firstOrNull { it !in setOf("pref", "voice", "internet") }.orEmpty()
}

data class VAdresse(
    val typen: List<String> = emptyList(),
    val postfach: String = "",
    val zusatz: String = "",
    val strasse: String = "",
    val ort: String = "",
    val region: String = "",
    val plz: String = "",
    val land: String = "",
    val bezeichnung: String = "",
    val bevorzugt: Boolean = false,
)

/**
 * Was die Rosenweg-Apps aus einer vCard (3.0 und 4.0) abbilden. [geburtstag]/[jahrestag] als
 * "JJJJ-MM-TT" oder ohne Jahr "--MM-TT". [foto] Base64 (JPEG/PNG). [kategorien] = Nextcloud-Gruppen
 * (CATEGORIES). [unbekannt]: alle uebrigen Zeilen unveraendert, damit beim Hochladen nichts verloren
 * geht. [istGruppe]: Gruppen-vCard (KIND:group, Apple) - wird nicht als Kontakt gezeigt.
 */
data class VKontakt(
    val uid: String,
    val anzeigename: String,
    val vorname: String = "",
    val nachname: String = "",
    val firma: String = "",
    val telefone: List<VEintrag> = emptyList(),
    val emails: List<VEintrag> = emptyList(),
    val notiz: String = "",
    val zweitname: String = "",
    val praefix: String = "",
    val suffix: String = "",
    val spitzname: String = "",
    val abteilung: String = "",
    val position: String = "",
    val adressen: List<VAdresse> = emptyList(),
    val webseiten: List<VEintrag> = emptyList(),
    val geburtstag: String? = null,
    val jahrestag: String? = null,
    val kategorien: List<String> = emptyList(),
    val foto: String? = null,
    val unbekannt: List<String> = emptyList(),
    val istGruppe: Boolean = false,
    /** Favorit (Android "starred"). Die Nextcloud kennt keinen Favoriten - wir schreiben X-ROSENWEG-FAVORIT:1. */
    val favorit: Boolean = false,
)

object VCard {
    /** Eigenschaften, die wir selbst schreiben; alles andere geht unveraendert zurueck. */
    private val BEKANNT = setOf("X-ROSENWEG-FAVORIT", "BEGIN", "END", "VERSION", "UID", "FN", "N", "NICKNAME", "ORG", "TITLE", "TEL", "EMAIL",
        "ADR", "URL", "BDAY", "ANNIVERSARY", "X-ANNIVERSARY", "NOTE", "CATEGORIES", "PHOTO", "PRODID", "REV", "LABEL")

    fun lesen(text: String): VKontakt? {
        val z = Zeilen.lesen(text)
        var uid = ""; var fn = ""; var vor = ""; var nach = ""; var zweit = ""; var prae = ""; var suff = ""
        var org = ""; var abt = ""; var titel = ""; var notiz = ""; var spitz = ""
        var bday: String? = null; var jahrestag: String? = null; var foto: String? = null; var gruppe = false
        var favorit = false
        val tel = mutableListOf<VEintrag>()
        val mail = mutableListOf<VEintrag>()
        val web = mutableListOf<VEintrag>()
        val adr = mutableListOf<VAdresse>()
        val kat = mutableListOf<String>()
        val unbekannt = mutableListOf<String>()
        // Apple: item1.TEL + item1.X-ABLabel:Buero
        val labels = z.filter { it.name == "X-ABLABEL" && it.gruppe.isNotEmpty() }.associate { it.gruppe to Zeilen.text(it.wert) }
        val verbrauchteLabels = mutableSetOf<String>()
        fun label(l: Zeile): String = labels[l.gruppe]?.let { verbrauchteLabels.add(l.gruppe); bereinigeLabel(it) }.orEmpty()
        for (l in z) when (l.name) {
            "UID" -> uid = l.wert.trim()
            "FN" -> fn = Zeilen.text(l.wert)
            "N" -> {
                val t = Zeilen.teilen(l.wert, ';')
                nach = t.getOrElse(0) { "" }; vor = t.getOrElse(1) { "" }; zweit = t.getOrElse(2) { "" }
                prae = t.getOrElse(3) { "" }; suff = t.getOrElse(4) { "" }
            }
            "NICKNAME" -> spitz = Zeilen.teilen(l.wert, ',').first()
            "ORG" -> Zeilen.teilen(l.wert, ';').let { org = it.getOrElse(0) { "" }; abt = it.drop(1).filter { s -> s.isNotEmpty() }.joinToString(", ") }
            "TITLE" -> titel = Zeilen.text(l.wert)
            "NOTE" -> notiz = Zeilen.text(l.wert)
            "TEL" -> tel.add(VEintrag(typen(l), l.wert.removePrefix("tel:").trim(), label(l), bevorzugt(l)))
            "EMAIL" -> mail.add(VEintrag(typen(l), l.wert.removePrefix("mailto:").trim(), label(l), bevorzugt(l)))
            "URL" -> web.add(VEintrag(typen(l), Zeilen.text(l.wert).trim(), label(l), bevorzugt(l)))
            "ADR" -> {
                val t = Zeilen.teilen(l.wert, ';')
                adr.add(VAdresse(typen(l), t.getOrElse(0) { "" }, t.getOrElse(1) { "" }, t.getOrElse(2) { "" },
                    t.getOrElse(3) { "" }, t.getOrElse(4) { "" }, t.getOrElse(5) { "" }, t.getOrElse(6) { "" }, label(l), bevorzugt(l)))
            }
            "BDAY" -> bday = datum(l) ?: run { unbekannt.add(l.roh); null }
            "ANNIVERSARY", "X-ANNIVERSARY" -> jahrestag = datum(l) ?: run { unbekannt.add(l.roh); null }
            "CATEGORIES" -> kat.addAll(Zeilen.teilen(l.wert, ',').map { it.trim() }.filter { it.isNotEmpty() })
            "PHOTO" -> foto = foto(l) ?: run { unbekannt.add(l.roh); null }
            "X-ROSENWEG-FAVORIT" -> favorit = l.wert.trim().let { it == "1" || it.equals("true", true) }
            "KIND", "X-ADDRESSBOOKSERVER-KIND" -> { if (l.wert.trim().equals("group", true)) gruppe = true; unbekannt.add(l.roh) }
            else -> if (l.name !in BEKANNT && l.name != "X-ABLABEL") unbekannt.add(l.roh)
        }
        // X-ABLabel ohne zugehoerige Eigenschaft unveraendert lassen
        z.filter { it.name == "X-ABLABEL" && it.gruppe !in verbrauchteLabels }.forEach { unbekannt.add(it.roh) }
        if (fn.isEmpty()) fn = listOf(vor, nach).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { org }
        if (fn.isEmpty() && tel.isEmpty() && mail.isEmpty() && !gruppe) return null
        return VKontakt(uid, fn, vor, nach, org, tel, mail, notiz, zweit, prae, suff, spitz, abt, titel, adr, web,
            bday, jahrestag, kat.distinct(), foto, unbekannt, gruppe, favorit)
    }

    private fun bereinigeLabel(s: String) = s.removePrefix("_\$!<").removeSuffix(">!\$_")

    private fun typen(l: Zeile): List<String> = l.liste("TYPE") + if (l.params["PREF"] != null) listOf("pref") else emptyList()

    private fun bevorzugt(l: Zeile) = "pref" in l.liste("TYPE") || l.params["PREF"] == "1"

    /** BDAY/ANNIVERSARY -> "JJJJ-MM-TT" oder "--MM-TT"; null = kein Datum (z.B. Text). */
    internal fun datum(l: Zeile): String? {
        val w = l.wert.trim().substringBefore('T')
        val ohneJahr = l.params["X-APPLE-OMIT-YEAR"]
        Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").matchEntire(w)?.let { m ->
            val (j, mo, t) = m.destructured
            return if (ohneJahr != null && ohneJahr == j) "--$mo-$t" else "$j-$mo-$t"
        }
        Regex("""^--(\d{2})-?(\d{2})$""").matchEntire(w)?.let { m -> return "--${m.groupValues[1]}-${m.groupValues[2]}" }
        return null
    }

    private fun foto(l: Zeile): String? {
        val w = l.wert.trim()
        val b64 = when {
            w.startsWith("data:", true) -> if (w.substringBefore(',').contains("base64", true)) w.substringAfter(',') else null
            l.params["ENCODING"]?.lowercase(Locale.ROOT) in setOf("b", "base64") -> w
            else -> null   // Link (VALUE=uri) - so lassen
        } ?: return null
        return try {
            Base64.getMimeDecoder().decode(b64)
            b64.filterNot { it.isWhitespace() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun fotoTyp(b64: String) = when {
        b64.startsWith("iVBOR") -> "PNG"
        b64.startsWith("R0lG") -> "GIF"
        else -> "JPEG"
    }

    fun schreiben(k: VKontakt): String {
        val m = Zeilen::maskieren
        val z = mutableListOf("BEGIN:VCARD", "VERSION:3.0", "PRODID:-//Rosenweg 4303//Rosenweg-Apps//DE",
            "UID:${k.uid.ifEmpty { UUID.randomUUID().toString() }}",
            "FN:${m(anzeigename(k))}", "N:${m(k.nachname)};${m(k.vorname)};${m(k.zweitname)};${m(k.praefix)};${m(k.suffix)}")
        if (k.spitzname.isNotEmpty()) z.add("NICKNAME:${m(k.spitzname)}")
        if (k.firma.isNotEmpty() || k.abteilung.isNotEmpty()) z.add("ORG:${m(k.firma)}" + if (k.abteilung.isNotEmpty()) ";${m(k.abteilung)}" else "")
        if (k.position.isNotEmpty()) z.add("TITLE:${m(k.position)}")
        var item = 0
        fun eintrag(name: String, e: VEintrag, wert: String) {
            val typen = (e.typen.filter { it != "pref" } + if (e.bevorzugt) listOf("pref") else emptyList()).distinct()
            val p = if (typen.isEmpty()) "" else ";TYPE=" + typen.joinToString(",") { it.uppercase(Locale.ROOT) }
            if (e.bezeichnung.isNotEmpty()) {
                item++
                z.add("rw$item.$name$p:$wert")
                z.add("rw$item.X-ABLabel:${m(e.bezeichnung)}")
            } else z.add("$name$p:$wert")
        }
        k.telefone.forEach { eintrag("TEL", it, it.wert) }
        k.emails.forEach { eintrag("EMAIL", it, it.wert) }
        k.adressen.forEach { a ->
            val wert = listOf(a.postfach, a.zusatz, a.strasse, a.ort, a.region, a.plz, a.land).joinToString(";") { m(it) }
            eintrag("ADR", VEintrag(a.typen, "", a.bezeichnung, a.bevorzugt), wert)
        }
        k.webseiten.forEach { eintrag("URL", it, it.wert) }
        k.geburtstag?.let { z.add(datumZeile("BDAY", it)) }
        k.jahrestag?.let { z.add(datumZeile("ANNIVERSARY", it)) }
        if (k.notiz.isNotEmpty()) z.add("NOTE:${m(k.notiz)}")
        if (k.kategorien.isNotEmpty()) z.add("CATEGORIES:" + k.kategorien.joinToString(",") { m(it) })
        k.foto?.let { z.add("PHOTO;ENCODING=b;TYPE=${fotoTyp(it)}:$it") }
        if (k.favorit) z.add("X-ROSENWEG-FAVORIT:1")
        z.addAll(k.unbekannt)
        z.add("REV:" + NextcloudDav.utc(System.currentTimeMillis()))
        z.add("END:VCARD")
        return Zeilen.falten(z)
    }

    /**
     * FN darf in vCard 3.0 nicht leer sein (A37: Kontakt nur mit Telefonnummer ging mit "FN:" hoch).
     * Rueckfall wie Androids display_name: Name, Firma, E-Mail, Telefonnummer, Spitzname.
     */
    internal fun anzeigename(k: VKontakt): String = k.anzeigename.trim()
        .ifEmpty { listOf(k.praefix, k.vorname, k.zweitname, k.nachname, k.suffix).filter { it.isNotBlank() }.joinToString(" ") }
        .ifEmpty { k.firma.trim() }
        .ifEmpty { k.emails.firstOrNull { it.wert.isNotBlank() }?.wert?.trim().orEmpty() }
        .ifEmpty { k.telefone.firstOrNull { it.wert.isNotBlank() }?.wert?.trim().orEmpty() }
        .ifEmpty { k.spitzname.trim() }
        .ifEmpty { "Kontakt" }

    /** vCard 3.0: Datum ohne Jahr wie Apple/Nextcloud mit X-APPLE-OMIT-YEAR. */
    private fun datumZeile(name: String, d: String) =
        if (d.startsWith("--")) "$name;X-APPLE-OMIT-YEAR=1604:1604-${d.removePrefix("--")}" else "$name:$d"
}
