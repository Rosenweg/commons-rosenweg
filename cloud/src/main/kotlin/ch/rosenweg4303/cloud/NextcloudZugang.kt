package ch.rosenweg4303.cloud

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nextcloud-Zugang auf dem eigenen Handy ueber die Rosenweg-Anmeldung (Stefan 26.11.2026): wer in
 * der App angemeldet ist (gemeinsamer Anmeldeschirm: Rosenweg-Karte oder Rosenweg-Konto), bekommt
 * das App-Passwort von der Anlage - auf demselben Weg wie die geteilten Telefone (pbx-api,
 * Nextcloud-Token-Dienst), nur mit dem Zugang der App statt der Sitzung am Telefon.
 * POST /api/telefon/privat/nextcloud -> {server, benutzer, app_passwort}. Abmelden widerruft das
 * App-Passwort wie bisher ueber WebDav.appPasswortWiderrufen.
 */
object NextcloudZugang {
    private const val ANLAGE = "https://pbx.rosenweg4303.ch"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).build()

    class Fehler(meldung: String, val status: Int) : Exception(meldung)

    /** Blockierend. Wirft [Fehler] (Text fuer die Person) oder IOException (Netz). */
    fun holen(c: Context, appName: String): Konto {
        val token = RosenwegAnmeldung.zugangsToken(c) ?: throw Fehler("Bitte zuerst anmelden", 401)
        val geraet = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
        val body = JSONObject().put("app", c.packageName).put("app_name", appName).put("geraet", geraet)
        val rq = Request.Builder().url("$ANLAGE/api/telefon/privat/nextcloud")
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(rq).execute().use { r ->
            val j = try { JSONObject(r.body?.string().orEmpty()) } catch (e: Exception) { JSONObject() }
            if (r.code == 401) RosenwegAnmeldung.zugangUngueltig()
            if (r.code != 200) throw Fehler(j.optString("error").ifEmpty { "Nextcloud-Zugang nicht möglich (${r.code})" }, r.code)
            val server = j.optString("server").trimEnd('/')
            val benutzer = j.optString("benutzer")
            val pw = j.optString("app_passwort")
            if (server != ROSENWEG_NEXTCLOUD || benutzer.isEmpty() || pw.isEmpty()) throw Fehler("Antwort der Anlage unvollständig", 502)
            return Konto(server, benutzer, pw)
        }
    }
}
