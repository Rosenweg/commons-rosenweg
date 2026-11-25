package ch.rosenweg4303.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Toast
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Anmeldung mit dem Rosenweg-Konto fuer alle Rosenweg-Apps (Stefan 25.11.2026: "gemeinsames
 * Cloud-Modul", Apps muessen auch ohne Rosenweg Geraet benutzbar sein).
 *
 * Authentik, Anwendung "rosenweg-apps" (oeffentlicher Client, PKCE S256). Ruecksprung
 * <Paketname>:/anmeldung -> [RosenwegAnmeldungActivity] (im Manifest dieses Moduls, Schema =
 * applicationId). Gespeichert wird nur das Refresh-Token, verschluesselt mit einem Schluessel
 * aus dem Android-Keystore; das Access-Token liegt nur im Speicher. Nie Tokens loggen.
 *
 * Auf geteilten Telefonen (Rosenweg Geraet installiert) melden sich die Apps NICHT selbst an,
 * dort laeuft alles ueber Geraet und die Sitzung am Telefon.
 */
object RosenwegAnmeldung {
    private const val TAG = "RosenwegAnmeldung"
    const val AUTHENTIK = "https://authentik.rosenweg4303.ch/application/o"
    private const val CLIENT_ID = "rosenweg-apps"
    private const val SCOPES = "openid email profile offline_access"

    private const val PREFS = "rosenweg_anmeldung_konto"
    private const val ALIAS = "rosenweg_anmeldung"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    @Volatile private var zugang: String? = null
    @Volatile private var zugangBis = 0L

    class Fehler(meldung: String) : Exception(meldung)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rueckSprung(c: Context) = "${c.packageName}:/anmeldung"

    /** Rosenweg Geraet installiert (geteiltes Telefon)? Dann keine eigene Anmeldung. */
    fun geraetDa(c: Context): Boolean = try {
        c.packageManager.resolveContentProvider("ch.rosenweg4303.telefon.anlage", 0) != null
    } catch (e: Exception) {
        false
    }

    fun angemeldet(c: Context) = prefs(c).getString("rt", null) != null

    fun name(c: Context): String? = prefs(c).getString("name", null)
    fun email(c: Context): String? = prefs(c).getString("email", null)

    private fun zufall(n: Int): String {
        val b = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun enc(s: String) = Uri.encode(s)

    /** Anmeldeseite im Browser oeffnen. */
    fun starten(a: Activity) {
        val verifier = zufall(48)
        val state = zufall(24)
        prefs(a).edit().putString("verifier", verifier).putString("state", state).commit()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val url = "$AUTHENTIK/authorize/?response_type=code&client_id=$CLIENT_ID" +
            "&redirect_uri=${enc(rueckSprung(a))}&scope=${enc(SCOPES)}" +
            "&state=$state&code_challenge=$challenge&code_challenge_method=S256"
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Ruecksprung auswerten (blockierend, nicht im UI-Thread). */
    fun abschliessen(c: Context, antwort: Uri) {
        val p = prefs(c)
        val verifier = p.getString("verifier", null)
        val state = p.getString("state", null)
        p.edit().remove("verifier").remove("state").commit()
        if (antwort.getQueryParameter("error") != null) throw Fehler("Anmeldung abgebrochen")
        val code = antwort.getQueryParameter("code") ?: throw Fehler("Anmeldung abgebrochen")
        if (verifier == null || state == null || antwort.getQueryParameter("state") != state) {
            throw Fehler("Anmeldung abgelaufen, bitte nochmals")
        }
        val j = token(
            FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", rueckSprung(c)).add("client_id", CLIENT_ID).add("code_verifier", verifier).build()
        ) ?: throw Fehler("Rosenweg-Anmeldung fehlgeschlagen")
        uebernehmen(c, j)
        // Name und E-Mail fuer die Anzeige
        try {
            http.newCall(Request.Builder().url("$AUTHENTIK/userinfo/").header("Authorization", "Bearer ${j.getString("access_token")}").build())
                .execute().use { r ->
                    if (r.isSuccessful) {
                        val u = JSONObject(r.body?.string().orEmpty())
                        p.edit().putString("name", u.optString("name")).putString("email", u.optString("email")).apply()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "userinfo: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "angemeldet")
    }

    /**
     * Gueltiges Access-Token fuer Anfragen an die Anlage (blockierend). Erneuert es mit dem
     * Refresh-Token; null = nicht (mehr) angemeldet.
     */
    @Synchronized
    fun zugangsToken(c: Context): String? {
        val jetzt = System.currentTimeMillis()
        zugang?.let { if (jetzt < zugangBis - 60_000) return it }
        val rt = entschluesseln(c, prefs(c).getString("rt", null) ?: return null) ?: return null
        val j = token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).add("client_id", CLIENT_ID).build())
        if (j == null) {
            Log.i(TAG, "Erneuern fehlgeschlagen")
            return null
        }
        uebernehmen(c, j)
        return zugang
    }

    /** Die Anlage hat das Token abgelehnt (z.B. Konto gesperrt): im Speicher verwerfen. */
    fun zugangUngueltig() {
        zugang = null
        zugangBis = 0
    }

    fun abmelden(c: Context) {
        zugangUngueltig()
        prefs(c).edit().clear().commit()
    }

    /** null bei 400/401 (abgelaufen/widerrufen) - dann ist die Anmeldung weg. Wirft bei Netzfehlern. */
    private fun token(body: FormBody): JSONObject? {
        http.newCall(Request.Builder().url("$AUTHENTIK/token/").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return JSONObject(text)
            Log.w(TAG, "Token: ${r.code}")
            return null
        }
    }

    private fun uebernehmen(c: Context, j: JSONObject) {
        zugang = j.getString("access_token")
        zugangBis = System.currentTimeMillis() + j.optLong("expires_in", 300) * 1000
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            prefs(c).edit().putString("rt", verschluesseln(it)).apply()
        }
    }

    // ─── Keystore ───────────────────────────────────────────────────────────

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun verschluesseln(s: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, schluessel())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun entschluesseln(ctx: Context, s: String): String? = try {
        val (iv, daten) = s.split(":", limit = 2)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(daten, Base64.NO_WRAP)))
    } catch (e: Exception) {
        Log.w(TAG, "Refresh-Token nicht lesbar: ${e.javaClass.simpleName}")
        prefs(ctx).edit().remove("rt").apply()
        null
    }
}

/** Ruecksprung der Anmeldung (<applicationId>:/anmeldung), keine Oberflaeche. */
class RosenwegAnmeldungActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val antwort = intent?.data
        if (antwort == null) {
            finish()
            return
        }
        val app = applicationContext
        Toast.makeText(app, "Anmelden …", Toast.LENGTH_SHORT).show()
        Thread {
            val meldung = try {
                RosenwegAnmeldung.abschliessen(app, antwort)
                "Angemeldet" + (RosenwegAnmeldung.name(app)?.takeIf { it.isNotEmpty() }?.let { " als $it" } ?: "")
            } catch (e: RosenwegAnmeldung.Fehler) {
                e.message ?: "Anmeldung fehlgeschlagen"
            } catch (e: Exception) {
                "Keine Verbindung zum Rosenweg"
            }
            runOnUiThread {
                Toast.makeText(app, meldung, Toast.LENGTH_LONG).show()
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
        }.start()
    }
}
