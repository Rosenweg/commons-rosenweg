package ch.rosenweg4303.cloud

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Nextcloud Login Flow v2: Die App holt eine Anmelde-Adresse, die Person meldet
 * sich im Browser an (bei uns ueber Authentik), die App fragt so lange nach, bis
 * die Nextcloud ein App-Passwort herausgibt. Das eigentliche Passwort sieht die
 * App nie.
 *
 * Blockierend — im Hintergrund aufrufen.
 */
class LoginFlow(private val appName: String, private val server: String = ROSENWEG_NEXTCLOUD) {
    /** Adresse, die im Browser geoeffnet werden muss. */
    lateinit var anmeldeAdresse: String
        private set
    private lateinit var token: String
    private lateinit var endpunkt: String
    private val ua = Http.ascii(appName)

    @Volatile
    var abgebrochen = false

    fun starten() {
        val r = Request.Builder().url("$server/index.php/login/v2").post(FormBody.Builder().build())
            .header("User-Agent", ua).build()
        Http.client.newCall(r).execute().use { a ->
            if (!a.isSuccessful) throw NextcloudFehler(a.code, "Anmeldung nicht moeglich")
            val j = JSONObject(a.body!!.string())
            anmeldeAdresse = j.getString("login")
            val poll = j.getJSONObject("poll")
            token = poll.getString("token")
            endpunkt = poll.getString("endpoint")
        }
        // Nur unsere Nextcloud: keine Umleitung auf einen fremden Server
        if (!anmeldeAdresse.startsWith("$server/") || !endpunkt.startsWith("$server/")) {
            throw NextcloudFehler(0, "Unerwarteter Server")
        }
    }

    /** Wartet bis zu [maxMinuten] auf die Anmeldung im Browser. */
    fun warten(maxMinuten: Int = 20): Konto {
        val ende = System.currentTimeMillis() + maxMinuten * 60_000L
        while (!abgebrochen && System.currentTimeMillis() < ende) {
            val r = Request.Builder().url(endpunkt).post(FormBody.Builder().add("token", token).build())
                .header("User-Agent", ua).build()
            try {
                Http.client.newCall(r).execute().use { a ->
                    if (a.code == 200) {
                        val j = JSONObject(a.body!!.string())
                        val s = j.getString("server").trimEnd('/')
                        if (s != server) throw NextcloudFehler(0, "Unerwarteter Server")
                        return Konto(s, j.getString("loginName"), j.getString("appPassword"))
                    }
                    // 404 = noch nicht angemeldet
                }
            } catch (e: IOException) {
                // Netz kurz weg — weiter versuchen
            }
            Thread.sleep(2000)
        }
        throw NextcloudFehler(0, if (abgebrochen) "Abgebrochen" else "Zeit abgelaufen")
    }
}
