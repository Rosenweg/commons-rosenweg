package ch.rosenweg4303.cloud.dav

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import java.util.Base64

/**
 * vCard <-> Data-Zeilen des Android-Adressbuchs (ContactsContract), ohne Android-Laufzeit
 * (Spaltennamen/Typen sind Konstanten) - damit in JVM-Tests pruefbar. Gruppen (CATEGORIES) und das
 * Foto in voller Groesse erledigt KontakteAbgleich (brauchen den Provider).
 */
internal object KontakteAbbildung {
    /** Eigener MIME-Typ fuer die nicht abgebildeten vCard-Zeilen (DATA1, durch Zeilenumbruch getrennt). */
    const val UNBEKANNT = "vnd.android.cursor.item/vnd.ch.rosenweg4303.vcard.unbekannt"

    /** Eine Data-Zeile: MIME-Typ und Spalten (ohne RAW_CONTACT_ID). */
    data class Daten(val mime: String, val werte: Map<String, Any?>)

    // ─── Typen ──────────────────────────────────────────────────────────────

    fun telefonTyp(e: VEintrag): Int {
        val t = e.typen.toSet()
        return when {
            e.bezeichnung.isNotEmpty() -> Phone.TYPE_CUSTOM
            "fax" in t -> if ("home" in t) Phone.TYPE_FAX_HOME else if ("work" in t) Phone.TYPE_FAX_WORK else Phone.TYPE_OTHER_FAX
            "pager" in t -> if ("work" in t) Phone.TYPE_WORK_PAGER else Phone.TYPE_PAGER
            "cell" in t || "mobile" in t || "iphone" in t -> if ("work" in t) Phone.TYPE_WORK_MOBILE else Phone.TYPE_MOBILE
            "x-assistant" in t || "assistant" in t -> Phone.TYPE_ASSISTANT
            "x-callback" in t -> Phone.TYPE_CALLBACK
            "x-company" in t -> Phone.TYPE_COMPANY_MAIN
            "x-radio" in t -> Phone.TYPE_RADIO
            "x-mms" in t -> Phone.TYPE_MMS
            "x-telex" in t -> Phone.TYPE_TELEX
            "textphone" in t || "x-tty" in t -> Phone.TYPE_TTY_TDD
            "car" in t -> Phone.TYPE_CAR
            "isdn" in t -> Phone.TYPE_ISDN
            "main" in t -> Phone.TYPE_MAIN
            "home" in t -> Phone.TYPE_HOME
            "work" in t -> Phone.TYPE_WORK
            else -> Phone.TYPE_OTHER
        }
    }

    fun telefonTypen(typ: Int): List<String> = when (typ) {
        Phone.TYPE_MOBILE -> listOf("cell")
        Phone.TYPE_HOME -> listOf("home")
        Phone.TYPE_WORK -> listOf("work")
        Phone.TYPE_FAX_HOME -> listOf("fax", "home")
        Phone.TYPE_FAX_WORK -> listOf("fax", "work")
        Phone.TYPE_OTHER_FAX -> listOf("fax")
        Phone.TYPE_PAGER -> listOf("pager")
        Phone.TYPE_WORK_PAGER -> listOf("pager", "work")
        Phone.TYPE_WORK_MOBILE -> listOf("cell", "work")
        Phone.TYPE_MAIN -> listOf("main")
        Phone.TYPE_CAR -> listOf("car")
        Phone.TYPE_ISDN -> listOf("isdn")
        Phone.TYPE_ASSISTANT -> listOf("x-assistant")
        Phone.TYPE_CALLBACK -> listOf("x-callback")
        Phone.TYPE_COMPANY_MAIN -> listOf("x-company")
        Phone.TYPE_RADIO -> listOf("x-radio")
        Phone.TYPE_MMS -> listOf("x-mms")
        Phone.TYPE_TELEX -> listOf("x-telex")
        Phone.TYPE_TTY_TDD -> listOf("textphone")
        else -> emptyList()
    }

    private fun emailTyp(e: VEintrag): Int {
        val t = e.typen.toSet()
        return when {
            e.bezeichnung.isNotEmpty() -> Email.TYPE_CUSTOM
            "home" in t -> Email.TYPE_HOME
            "work" in t -> Email.TYPE_WORK
            "x-mobile" in t || "cell" in t -> Email.TYPE_MOBILE
            else -> Email.TYPE_OTHER
        }
    }

    private fun emailTypen(typ: Int) = when (typ) {
        Email.TYPE_HOME -> listOf("home")
        Email.TYPE_WORK -> listOf("work")
        Email.TYPE_MOBILE -> listOf("x-mobile")
        else -> emptyList()
    }

    private fun adressTyp(a: VAdresse) = when {
        a.bezeichnung.isNotEmpty() -> StructuredPostal.TYPE_CUSTOM
        "home" in a.typen -> StructuredPostal.TYPE_HOME
        "work" in a.typen -> StructuredPostal.TYPE_WORK
        else -> StructuredPostal.TYPE_OTHER
    }

    private fun adressTypen(typ: Int) = when (typ) {
        StructuredPostal.TYPE_HOME -> listOf("home")
        StructuredPostal.TYPE_WORK -> listOf("work")
        else -> emptyList()
    }

    // ─── vCard -> Android ───────────────────────────────────────────────────

    /** Data-Zeilen ohne Gruppen; das Foto als Photo.PHOTO-Bytes (der Abgleich verkleinert, wenn noetig). */
    fun nachAndroid(k: VKontakt): List<Daten> {
        val d = mutableListOf<Daten>()
        d.add(Daten(StructuredName.CONTENT_ITEM_TYPE, mapOf(
            StructuredName.DISPLAY_NAME to k.anzeigename, StructuredName.GIVEN_NAME to k.vorname.ifEmpty { null },
            StructuredName.FAMILY_NAME to k.nachname.ifEmpty { null }, StructuredName.MIDDLE_NAME to k.zweitname.ifEmpty { null },
            StructuredName.PREFIX to k.praefix.ifEmpty { null }, StructuredName.SUFFIX to k.suffix.ifEmpty { null })))
        if (k.spitzname.isNotEmpty()) d.add(Daten(Nickname.CONTENT_ITEM_TYPE, mapOf(Nickname.NAME to k.spitzname, Nickname.TYPE to Nickname.TYPE_DEFAULT)))
        if (k.firma.isNotEmpty() || k.abteilung.isNotEmpty() || k.position.isNotEmpty()) d.add(Daten(Organization.CONTENT_ITEM_TYPE, mapOf(
            Organization.COMPANY to k.firma.ifEmpty { null }, Organization.DEPARTMENT to k.abteilung.ifEmpty { null },
            Organization.TITLE to k.position.ifEmpty { null }, Organization.TYPE to Organization.TYPE_WORK)))
        k.telefone.filter { it.wert.isNotBlank() }.forEach { e ->
            d.add(Daten(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to e.wert, Phone.TYPE to telefonTyp(e),
                Phone.LABEL to e.bezeichnung.ifEmpty { null }, Data.IS_PRIMARY to if (e.bevorzugt) 1 else 0)))
        }
        k.emails.filter { it.wert.isNotBlank() }.forEach { e ->
            d.add(Daten(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to e.wert, Email.TYPE to emailTyp(e),
                Email.LABEL to e.bezeichnung.ifEmpty { null }, Data.IS_PRIMARY to if (e.bevorzugt) 1 else 0)))
        }
        k.adressen.forEach { a ->
            val formatiert = listOf(a.postfach, a.strasse, a.zusatz, listOf(a.plz, a.ort).filter { it.isNotEmpty() }.joinToString(" "),
                a.region, a.land).filter { it.isNotBlank() }.joinToString("\n")
            d.add(Daten(StructuredPostal.CONTENT_ITEM_TYPE, mapOf(
                StructuredPostal.POBOX to a.postfach.ifEmpty { null }, StructuredPostal.NEIGHBORHOOD to a.zusatz.ifEmpty { null },
                StructuredPostal.STREET to a.strasse.ifEmpty { null }, StructuredPostal.CITY to a.ort.ifEmpty { null },
                StructuredPostal.REGION to a.region.ifEmpty { null }, StructuredPostal.POSTCODE to a.plz.ifEmpty { null },
                StructuredPostal.COUNTRY to a.land.ifEmpty { null }, StructuredPostal.FORMATTED_ADDRESS to formatiert,
                StructuredPostal.TYPE to adressTyp(a), StructuredPostal.LABEL to a.bezeichnung.ifEmpty { null },
                Data.IS_PRIMARY to if (a.bevorzugt) 1 else 0)))
        }
        k.webseiten.filter { it.wert.isNotBlank() }.forEach { e ->
            d.add(Daten(Website.CONTENT_ITEM_TYPE, mapOf(Website.URL to e.wert,
                Website.TYPE to when { e.bezeichnung.isNotEmpty() -> Website.TYPE_CUSTOM; "home" in e.typen -> Website.TYPE_HOME
                    "work" in e.typen -> Website.TYPE_WORK; else -> Website.TYPE_HOMEPAGE },
                Website.LABEL to e.bezeichnung.ifEmpty { null })))
        }
        k.geburtstag?.let { d.add(Daten(Event.CONTENT_ITEM_TYPE, mapOf(Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY))) }
        k.jahrestag?.let { d.add(Daten(Event.CONTENT_ITEM_TYPE, mapOf(Event.START_DATE to it, Event.TYPE to Event.TYPE_ANNIVERSARY))) }
        if (k.notiz.isNotEmpty()) d.add(Daten(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to k.notiz)))
        k.foto?.let { b64 ->
            try { d.add(Daten(Photo.CONTENT_ITEM_TYPE, mapOf(Photo.PHOTO to Base64.getMimeDecoder().decode(b64)))) } catch (e: IllegalArgumentException) { /* kaputt */ }
        }
        if (k.unbekannt.isNotEmpty()) d.add(Daten(UNBEKANNT, mapOf(Data.DATA1 to k.unbekannt.joinToString("\n"))))
        return d
    }

    // ─── Android -> vCard ───────────────────────────────────────────────────

    private fun s(m: Map<String, Any?>, k: String) = m[k]?.toString().orEmpty()
    private fun i(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toInt() ?: m[k]?.toString()?.toIntOrNull() ?: 0

    /** Kontakt aus den Data-Zeilen; [foto] (volle Groesse) ersetzt das Vorschaubild, [kategorien] = Gruppen. */
    fun vonAndroid(uid: String, zeilen: List<Daten>, kategorien: List<String>, foto: ByteArray? = null): VKontakt {
        var fn = ""; var vor = ""; var nach = ""; var zweit = ""; var prae = ""; var suff = ""
        var spitz = ""; var firma = ""; var abt = ""; var pos = ""; var notiz = ""
        var bday: String? = null; var jahrestag: String? = null
        var bild: ByteArray? = foto
        val tel = mutableListOf<VEintrag>(); val mail = mutableListOf<VEintrag>(); val web = mutableListOf<VEintrag>()
        val adr = mutableListOf<VAdresse>(); val unbekannt = mutableListOf<String>()
        for (z in zeilen) {
            val w = z.werte
            val prim = i(w, Data.IS_PRIMARY) == 1
            when (z.mime) {
                StructuredName.CONTENT_ITEM_TYPE -> {
                    fn = s(w, StructuredName.DISPLAY_NAME); vor = s(w, StructuredName.GIVEN_NAME); nach = s(w, StructuredName.FAMILY_NAME)
                    zweit = s(w, StructuredName.MIDDLE_NAME); prae = s(w, StructuredName.PREFIX); suff = s(w, StructuredName.SUFFIX)
                }
                Nickname.CONTENT_ITEM_TYPE -> if (spitz.isEmpty()) spitz = s(w, Nickname.NAME)
                Organization.CONTENT_ITEM_TYPE -> if (firma.isEmpty() && pos.isEmpty()) {
                    firma = s(w, Organization.COMPANY); abt = s(w, Organization.DEPARTMENT); pos = s(w, Organization.TITLE)
                }
                Phone.CONTENT_ITEM_TYPE -> s(w, Phone.NUMBER).takeIf { it.isNotBlank() }?.let { n ->
                    val t = i(w, Phone.TYPE)
                    tel.add(VEintrag(telefonTypen(t), n, if (t == Phone.TYPE_CUSTOM) s(w, Phone.LABEL) else "", prim))
                }
                Email.CONTENT_ITEM_TYPE -> s(w, Email.ADDRESS).takeIf { it.isNotBlank() }?.let { a ->
                    val t = i(w, Email.TYPE)
                    mail.add(VEintrag(listOf("internet") + emailTypen(t), a, if (t == Email.TYPE_CUSTOM) s(w, Email.LABEL) else "", prim))
                }
                StructuredPostal.CONTENT_ITEM_TYPE -> {
                    val t = i(w, StructuredPostal.TYPE)
                    var strasse = s(w, StructuredPostal.STREET)
                    // nur die formatierte Adresse (manche Apps): als Strasse uebernehmen
                    if (listOf(StructuredPostal.STREET, StructuredPostal.CITY, StructuredPostal.POSTCODE, StructuredPostal.POBOX)
                            .all { s(w, it).isEmpty() }) strasse = s(w, StructuredPostal.FORMATTED_ADDRESS)
                    adr.add(VAdresse(adressTypen(t), s(w, StructuredPostal.POBOX), s(w, StructuredPostal.NEIGHBORHOOD), strasse,
                        s(w, StructuredPostal.CITY), s(w, StructuredPostal.REGION), s(w, StructuredPostal.POSTCODE),
                        s(w, StructuredPostal.COUNTRY), if (t == StructuredPostal.TYPE_CUSTOM) s(w, StructuredPostal.LABEL) else "", prim))
                }
                Website.CONTENT_ITEM_TYPE -> s(w, Website.URL).takeIf { it.isNotBlank() }?.let { u ->
                    val t = i(w, Website.TYPE)
                    web.add(VEintrag(when (t) { Website.TYPE_HOME -> listOf("home"); Website.TYPE_WORK -> listOf("work"); else -> emptyList() },
                        u, if (t == Website.TYPE_CUSTOM) s(w, Website.LABEL) else ""))
                }
                Event.CONTENT_ITEM_TYPE -> {
                    val d = datum(s(w, Event.START_DATE))
                    when (i(w, Event.TYPE)) {
                        Event.TYPE_BIRTHDAY -> if (d != null) bday = d
                        Event.TYPE_ANNIVERSARY -> if (d != null) jahrestag = d
                    }
                }
                Note.CONTENT_ITEM_TYPE -> if (notiz.isEmpty()) notiz = s(w, Note.NOTE)
                Photo.CONTENT_ITEM_TYPE -> if (bild == null) bild = w[Photo.PHOTO] as? ByteArray
                UNBEKANNT -> unbekannt.addAll(s(w, Data.DATA1).split("\n").filter { it.isNotBlank() })
            }
        }
        val name = fn.ifEmpty { listOf(prae, vor, zweit, nach, suff).filter { it.isNotBlank() }.joinToString(" ") }.ifEmpty { firma }
        return VKontakt(uid, name, vor, nach, firma, tel, mail, notiz, zweit, prae, suff, spitz, abt, pos, adr, web, bday, jahrestag,
            kategorien.distinct(), bild?.let { Base64.getEncoder().encodeToString(it) }, unbekannt)
    }

    /** Android-Datum (JJJJ-MM-TT, --MM-TT, JJJJMMTT, auch mit Uhrzeit) vereinheitlichen. */
    fun datum(s: String): String? {
        val w = s.trim().substringBefore('T').substringBefore(' ')
        Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").matchEntire(w)?.let { val (j, m, t) = it.destructured; return "$j-$m-$t" }
        Regex("""^--(\d{2})-?(\d{2})$""").matchEntire(w)?.let { val (m, t) = it.destructured; return "--$m-$t" }
        return null
    }
}
