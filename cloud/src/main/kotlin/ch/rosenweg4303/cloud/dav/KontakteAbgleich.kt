package ch.rosenweg4303.cloud.dav

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import ch.rosenweg4303.cloud.Konto
import java.util.UUID

/**
 * Adressbuecher der Rosenweg-Nextcloud im Android-Adressbuch (ContactsContract), in beide
 * Richtungen, ohne DAVx5 (Stefan: die Apps sprechen CardDAV selbst). Muster wie das Telefonbuch
 * von Rosenweg Geraet (Kontakte.java): eigenes Konto, Zeilen als Sync-Adapter.
 *
 * - Konto "Rosenweg-Nextcloud" vom Typ [kontoTyp]. Die App muss dafuer einen
 *   AccountAuthenticator mit genau diesem Typ deklarieren (sonst verweigert Android das Konto).
 * - Zuerst hoch: in der App geaenderte (DIRTY) und geloeschte (DELETED) Kontakte per PUT/DELETE
 *   mit If-Match; bei 412 gewinnt der Server. Neue Kontakte landen im ersten beschreibbaren Buch.
 * - Dann herunter: alle vCards je Buch, verglichen ueber den ETag; was fehlt, verschwindet lokal.
 * RawContacts: SOURCE_ID = Adresse der .vcf, SYNC1 = ETag, SYNC2 = UID, SYNC3 = Adresse des Buchs.
 * Abgeglichen werden Name, Firma, Telefonnummern, E-Mail-Adressen und Notiz.
 *
 * Braucht READ_CONTACTS/WRITE_CONTACTS. Blockiert - im Hintergrund aufrufen.
 */
class KontakteAbgleich(private val c: Context, konto: Konto, appName: String, private val kontoTyp: String) {
    companion object {
        private const val TAG = "RosenwegKontakte"
        const val KONTO_NAME = "Rosenweg-Nextcloud"

        /** Abmelden: Konto samt Kontakten weg (die Daten bleiben auf dem Server). */
        fun entfernen(c: Context, kontoTyp: String) {
            val am = AccountManager.get(c)
            am.getAccountsByType(kontoTyp).forEach { am.removeAccountExplicitly(it) }
        }
    }

    data class Ergebnis(val buecher: Int, val hoch: Int, val herunter: Int, val geloescht: Int)

    private val dav = NextcloudDav(konto, appName)
    private val cr = c.contentResolver

    private fun alsAdapter(u: Uri): Uri = u.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, KONTO_NAME)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, kontoTyp).build()

    private fun kontoAnlegen() {
        val am = AccountManager.get(c)
        if (am.getAccountsByType(kontoTyp).isNotEmpty()) return
        am.addAccountExplicitly(Account(KONTO_NAME, kontoTyp), null, null)
        // Kontakte ohne Gruppe sichtbar (sonst blenden Telefon- und Kontakte-Apps das Konto aus)
        cr.insert(ContactsContract.Settings.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, KONTO_NAME)
            put(ContactsContract.Settings.ACCOUNT_TYPE, kontoTyp)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        })
    }

    fun abgleichen(): Ergebnis {
        kontoAnlegen()
        val buecher = dav.sammlungen(NextcloudDav.Art.ADRESSEN)
        val hoch = hochladen(buecher.firstOrNull { !it.nurLesen })
        var n = 0; var weg = 0
        val lokal = lokaleKontakte()
        val gesehen = mutableSetOf<String>()
        for (b in buecher) {
            for (o in dav.adressen(b.url)) {
                gesehen.add(o.href)
                val alt = lokal[o.href]
                if (alt != null && alt.second == o.etag && o.etag.isNotEmpty()) continue
                val k = VCard.lesen(o.daten) ?: continue
                if (alt != null) cr.delete(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), alt.first), null, null)
                einfuegen(k, o, b.url)
                n++
            }
        }
        // auf dem Server geloescht (oder Buch weg)
        lokal.filterKeys { it !in gesehen }.values.forEach { (id, _) ->
            cr.delete(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), id), null, null); weg++
        }
        Log.i(TAG, "Adressbuecher: ${buecher.size}, hoch $hoch, herunter $n, geloescht $weg")
        return Ergebnis(buecher.size, hoch, n, weg)
    }

    /** href -> (RawContact-Id, ETag); ohne lokal geaenderte (DIRTY), die noch hoch muessen. */
    private fun lokaleKontakte(): Map<String, Pair<Long, String>> = cr.query(alsAdapter(RawContacts.CONTENT_URI),
        arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.DELETED),
        "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?", arrayOf(kontoTyp, KONTO_NAME), null)?.use { cur ->
        buildMap {
            while (cur.moveToNext()) {
                val href = cur.getString(1) ?: continue
                if (cur.getInt(3) == 1 || cur.getInt(4) == 1) continue
                put(href, cur.getLong(0) to cur.getString(2).orEmpty())
            }
        }
    } ?: emptyMap()

    // ─── hoch ───────────────────────────────────────────────────────────────

    private fun hochladen(standardBuch: NextcloudDav.Sammlung?): Int {
        var n = 0
        cr.query(alsAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.DELETED, RawContacts.SYNC3),
            "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=? AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)",
            arrayOf(kontoTyp, KONTO_NAME), null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val href = cur.getString(1)
                val etag = cur.getString(2)
                val zeile = ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), id)
                try {
                    if (cur.getInt(4) == 1) {
                        if (href != null) dav.loeschen(href, etag)
                        cr.delete(zeile, null, null)
                    } else {
                        val buch = cur.getString(5) ?: standardBuch?.url ?: continue
                        val k = lesen(id, cur.getString(3)?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString())
                        val ziel = href ?: (buch + k.uid + ".vcf")
                        val neu = dav.schreiben(ziel, if (href == null) null else etag.orEmpty(), VCard.schreiben(k), "text/vcard; charset=utf-8")
                        cr.update(zeile, ContentValues().apply {
                            put(RawContacts.SOURCE_ID, ziel)
                            put(RawContacts.SYNC1, neu.orEmpty())   // 412: leer -> der Server ersetzt beim Herunterladen
                            put(RawContacts.SYNC2, k.uid)
                            put(RawContacts.SYNC3, buch)
                            put(RawContacts.DIRTY, 0)
                        }, null, null)
                    }
                    n++
                } catch (e: ch.rosenweg4303.cloud.NextcloudFehler) {
                    if (e.anmeldungNoetig) throw e
                    Log.w(TAG, "hoch: ${e.code}")
                }
            }
        }
        return n
    }

    /** Kontakt aus den Data-Zeilen eines RawContacts. */
    private fun lesen(rawId: Long, uid: String): VKontakt {
        var fn = ""; var vor = ""; var nach = ""; var firma = ""; var notiz = ""
        val tel = mutableListOf<Pair<String, String>>()
        val mail = mutableListOf<Pair<String, String>>()
        cr.query(Data.CONTENT_URI, arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val d1 = cur.getString(1).orEmpty()
                when (cur.getString(0)) {
                    StructuredName.CONTENT_ITEM_TYPE -> { fn = d1; vor = cur.getString(2).orEmpty(); nach = cur.getString(3).orEmpty() }
                    Phone.CONTENT_ITEM_TYPE -> tel.add(telTyp(cur.getInt(2)) to d1)
                    Email.CONTENT_ITEM_TYPE -> mail.add((if (cur.getInt(2) == Email.TYPE_WORK) "work" else if (cur.getInt(2) == Email.TYPE_HOME) "home" else "") to d1)
                    Organization.CONTENT_ITEM_TYPE -> firma = d1
                    Note.CONTENT_ITEM_TYPE -> notiz = d1
                }
            }
        }
        return VKontakt(uid, fn.ifEmpty { listOf(vor, nach).filter { it.isNotBlank() }.joinToString(" ") }, vor, nach, firma, tel, mail, notiz)
    }

    private fun telTyp(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "cell"; Phone.TYPE_HOME -> "home"; Phone.TYPE_WORK -> "work"; else -> ""
    }

    private fun telTyp(s: String) = when (s) {
        "cell", "mobile", "iphone" -> Phone.TYPE_MOBILE; "home" -> Phone.TYPE_HOME; "work" -> Phone.TYPE_WORK; else -> Phone.TYPE_OTHER
    }

    // ─── herunter ───────────────────────────────────────────────────────────

    private fun einfuegen(k: VKontakt, o: NextcloudDav.Objekt, buch: String) {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(alsAdapter(RawContacts.CONTENT_URI))
            .withValue(RawContacts.ACCOUNT_NAME, KONTO_NAME).withValue(RawContacts.ACCOUNT_TYPE, kontoTyp)
            .withValue(RawContacts.SOURCE_ID, o.href).withValue(RawContacts.SYNC1, o.etag)
            .withValue(RawContacts.SYNC2, k.uid).withValue(RawContacts.SYNC3, buch).build())
        fun daten(typ: String) = ContentProviderOperation.newInsert(alsAdapter(Data.CONTENT_URI))
            .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, typ)
        ops.add(daten(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, k.anzeigename)
            .withValue(StructuredName.GIVEN_NAME, k.vorname).withValue(StructuredName.FAMILY_NAME, k.nachname).build())
        if (k.firma.isNotEmpty()) ops.add(daten(Organization.CONTENT_ITEM_TYPE).withValue(Organization.COMPANY, k.firma).build())
        k.telefone.forEach { (t, n) -> ops.add(daten(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, n).withValue(Phone.TYPE, telTyp(t)).build()) }
        k.emails.forEach { (t, e) ->
            ops.add(daten(Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, e)
                .withValue(Email.TYPE, if (t == "work") Email.TYPE_WORK else if (t == "home") Email.TYPE_HOME else Email.TYPE_OTHER).build())
        }
        if (k.notiz.isNotEmpty()) ops.add(daten(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, k.notiz).build())
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
    }
}
