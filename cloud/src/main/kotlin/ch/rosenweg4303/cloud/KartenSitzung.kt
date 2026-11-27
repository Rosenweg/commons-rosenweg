package ch.rosenweg4303.cloud

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * Anmeldung dieser App mit der Rosenweg-Karte auf einem privaten Handy (ohne Rosenweg Geraet).
 *
 * Stefan 26.11.2026: derselbe Anmeldeschirm wie der Sperrbildschirm der geteilten Telefone; die
 * Karte allein meldet an (ohne PIN). Jede App hat ihre EIGENE Sitzung - abmelden in einer App
 * betrifft nur diese App. Server: Website api/routes/app-karte.js.
 *
 * Bindung ans Geraet: Schluesselpaar EC P-256 im Android-Keystore dieser App (nicht
 * exportierbar). Der Server kennt nur den oeffentlichen Schluessel. Der Zugang (rw_app_...) gilt
 * 15 Minuten und liegt nur im Speicher; erneuern geht nur mit einer Unterschrift ueber eine
 * Herausforderung des Servers. Gespeichert wird nur die Sitzungs-Id (allein nutzlos). Die
 * Karten-UID wird nie gespeichert oder geloggt.
 *
 * Dritter Weg (Stefan 27.11.2026), wie "QR-Code scannen" am Sperrbildschirm: [anmeldenMitQr] loest
 * einen Anmelde-QR ein, den das Portal (rosenweg4303.ch/smartphones, "App auf eigenem Handy") auf
 * einem anderen Geraet zeigt, an dem die Person angemeldet ist. Danach ist es dieselbe Sitzung wie
 * mit der Karte (Schluessel, Erneuern, Abmelden); der Server prueft dann statt der Karte das Konto.
 */
object KartenSitzung {
    private const val TAG = "RosenwegKarte"
    const val API = "https://rosenweg4303.ch"
    private const val PREFS = "rosenweg_anmeldung_karte"
    private const val ALIAS_PRAEFIX = "rosenweg_karte_"

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var zugang: String? = null
    @Volatile private var zugangBis = 0L

    /** Antwort des Servers mit Text fuer die Person (und Status fuer die Anzeige). */
    class Fehler(meldung: String, val status: Int = 0) : Exception(meldung)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun angemeldet(c: Context) = prefs(c).getString("sitzung", null) != null
    fun name(c: Context): String? = prefs(c).getString("name", null)
    fun email(c: Context): String? = prefs(c).getString("email", null)
    /** Wie angemeldet: "mit Karte" oder "mit QR-Code" (null: aeltere Sitzung, mit Karte). */
    fun weg(c: Context): String? = prefs(c).getString("weg", null)

    /**
     * Kennung des Geraets fuer "neues Geraet" (Mail an die Person): sha256 der ANDROID_ID. Seit
     * Android 8 ist sie je Signaturschluessel - alle Rosenweg-Apps eines Handys haben dieselbe.
     */
    fun geraetKennung(c: Context): String {
        @Suppress("HardwareIds")
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unbekannt"
        return MessageDigest.getInstance("SHA-256").digest("rosenweg:$id".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun geraetName(): String {
        val m = Build.MODEL ?: ""
        val h = Build.MANUFACTURER ?: ""
        return (if (m.startsWith(h, ignoreCase = true)) m else "$h $m").trim().ifEmpty { "Android" }
    }

    private fun appName(c: Context): String = try {
        c.packageManager.getApplicationLabel(c.applicationInfo).toString()
    } catch (e: Exception) {
        c.packageName
    }

    // ─── Schluessel ─────────────────────────────────────────────────────────

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Neues Schluesselpaar unter einem eigenen Namen je Anmeldung (ein Abmelden im Hintergrund
     * loescht so nie den Schluessel einer neueren Anmeldung) -> oeffentlicher Schluessel als
     * SPKI/DER Base64.
     */
    private fun neuerSchluessel(alias: String): String {
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        g.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return Base64.encodeToString(g.generateKeyPair().public.encoded, Base64.NO_WRAP)
    }

    private fun schluesselWeg(alias: String?) {
        if (alias == null) return
        try { keystore().deleteEntry(alias) } catch (e: Exception) { }
    }

    private fun unterschreiben(alias: String, text: String): String {
        val k = keystore().getKey(alias, null) as? PrivateKey ?: throw Fehler("Geräteschlüssel fehlt – bitte neu anmelden", 401)
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(k)
        s.update(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    // ─── Server ─────────────────────────────────────────────────────────────

    private fun post(pfad: String, body: JSONObject, token: String? = null): Pair<Int, JSONObject> {
        val r = Request.Builder().url("$API$pfad").post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
        if (token != null) r.header("Authorization", "Bearer $token")
        http.newCall(r.build()).execute().use { a ->
            val text = a.body?.string().orEmpty()
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            return a.code to j
        }
    }

    /** Praefix des Anmelde-QR fuer Apps (geteilte Telefone: "RWTEL1:", nicht austauschbar). */
    const val QR_PRAEFIX = "RWAPP1:"
    const val QR_PRAEFIX_TELEFON = "RWTEL1:"

    /**
     * Karte einloesen (blockierend, nicht im UI-Thread). Wirft [Fehler] mit Text fuer die Person.
     * Eine schon bestehende Karten-Sitzung dieser App wird am Server ersetzt.
     */
    fun anmelden(c: Context, uid: String) = sitzungHolen(c, "/api/apps/karte-anmelden", "uid", uid, "mit Karte")

    /**
     * Gescannten Anmelde-QR ("RWAPP1:...") einloesen (blockierend, nicht im UI-Thread). Der QR ist
     * einmalig und kurz gueltig; wirft [Fehler] mit Text fuer die Person.
     */
    fun anmeldenMitQr(c: Context, inhalt: String) = sitzungHolen(c, "/api/apps/qr-anmelden", "token", inhalt, "mit QR-Code")

    private fun sitzungHolen(c: Context, pfad: String, feld: String, wert: String, wie: String) {
        val alias = ALIAS_PRAEFIX + java.util.UUID.randomUUID().toString().replace("-", "")
        val schluessel = neuerSchluessel(alias)
        val body = JSONObject()
            .put(feld, wert)
            .put("app", c.packageName)
            .put("app_name", appName(c))
            .put("schluessel", schluessel)
            .put("geraet", JSONObject().put("kennung", geraetKennung(c)).put("name", geraetName())
                .put("plattform", "Android ${Build.VERSION.RELEASE}"))
        val (status, j) = try {
            post(pfad, body)
        } catch (e: java.io.IOException) {
            schluesselWeg(alias)
            throw Fehler("Keine Verbindung zum Rosenweg")
        }
        if (status != 200) {
            schluesselWeg(alias)
            throw Fehler(j.optString("error").ifEmpty { "Anmelden fehlgeschlagen ($status)" }, status)
        }
        val alt = prefs(c).getString("alias", null)
        uebernehmen(j)
        prefs(c).edit().putString("sitzung", j.getString("sitzung")).putString("alias", alias)
            .putString("name", j.optString("name")).putString("email", j.optString("email"))
            .putString("weg", wie).apply()
        if (alt != alias) schluesselWeg(alt)   // die alte Sitzung hat der Server ersetzt
        Log.i(TAG, "$wie angemeldet")
    }

    private fun uebernehmen(j: JSONObject) {
        zugang = j.getString("zugang")
        zugangBis = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000
    }

    /** Gueltiger Zugang (blockierend). null = nicht (mehr) angemeldet; wirft bei Netzfehlern. */
    @Synchronized
    fun zugangsToken(c: Context): String? {
        zugang?.let { if (System.currentTimeMillis() < zugangBis - 60_000) return it }
        val id = prefs(c).getString("sitzung", null) ?: return null
        val alias = prefs(c).getString("alias", null) ?: return weg(c, "Schluessel fehlt")
        val j = erneuern(id, alias) ?: return weg(c, "Sitzung beendet")
        uebernehmen(j)
        return zugang
    }

    /** Zwei Schritte: Herausforderung holen, unterschreiben. null = Sitzung am Server beendet. */
    private fun erneuern(id: String, alias: String): JSONObject? {
        val (s1, h) = post("/api/apps/sitzung/erneuern", JSONObject().put("sitzung", id))
        if (s1 == 401) return null.also { Log.i(TAG, "Erneuern: ${h.optString("grund")}") }
        if (s1 != 200) throw java.io.IOException("Erneuern: $s1")
        val herausforderung = h.getString("herausforderung")
        val unterschrift = try {
            unterschreiben(alias, "rw-app-sitzung-erneuern\n$id\n$herausforderung")
        } catch (e: Fehler) {
            return null
        }
        val (s2, j) = post("/api/apps/sitzung/erneuern", JSONObject().put("sitzung", id)
            .put("herausforderung", herausforderung).put("unterschrift", unterschrift))
        if (s2 == 401) return null.also { Log.i(TAG, "Erneuern: ${j.optString("grund")}") }
        if (s2 != 200) throw java.io.IOException("Erneuern: $s2")
        return j
    }

    private fun weg(c: Context, warum: String): String? {
        Log.i(TAG, warum)
        vergessen(c)
        return null
    }

    fun zugangUngueltig() {
        zugang = null
        zugangBis = 0
    }

    private fun vergessen(c: Context) {
        zugangUngueltig()
        val alias = prefs(c).getString("alias", null)
        prefs(c).edit().clear().commit()
        schluesselWeg(alias)
    }

    /**
     * Abmelden: hier sofort (darf im UI-Thread laufen), am Server im Hintergrund. Fehler dort
     * sind egal - ohne Schluessel kann die Sitzung ohnehin nicht mehr erneuert werden.
     */
    fun abmelden(c: Context) {
        val p = prefs(c)
        val id = p.getString("sitzung", null) ?: return
        val alias = p.getString("alias", null)
        val z = zugang?.takeIf { System.currentTimeMillis() < zugangBis - 10_000 }
        zugangUngueltig()
        p.edit().clear().commit()
        Thread {
            try {
                val token = z ?: alias?.let { erneuern(id, it)?.optString("zugang") }
                if (!token.isNullOrEmpty()) post("/api/apps/sitzung/abmelden", JSONObject(), token)
            } catch (e: Exception) {
                Log.w(TAG, "Abmelden am Server: ${e.javaClass.simpleName}")
            } finally {
                schluesselWeg(alias)
            }
        }.start()
    }
}
