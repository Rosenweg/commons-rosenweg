package ch.rosenweg4303.cloud

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Die Einstellungen einer App gehoeren der Person und liegen in ihrer
 * Nextcloud: Persönlich/.rosenweg/<paket>.json. Auf einem geteilten Telefon
 * sind sie nach dem Anmelden wieder da, auf einem anderen Telefon genauso.
 *
 * - Beim Start ([abgleichen]): ist die Fassung in der Nextcloud neuer als die
 *   letzte Aenderung hier, wird sie uebernommen; sonst wird die hiesige
 *   hochgeladen. Es gilt die zuletzt geaenderte Fassung.
 * - Jede Aenderung wird kurz danach hochgeladen.
 * - Geraetebezogenes bleibt auf dem Geraet (Widgets, Pfade, Zaehler, "zuletzt",
 *   Hinweise ...), siehe [istGeraetebezogen].
 *
 * Ohne Anmeldung passiert nichts. Fossify-Apps speichern in "Prefs".
 */
object Einstellungen {
    private const val TAG = "RosenwegEinstellungen"
    private const val STAND = "rosenweg_cloud_einstellungen"
    private const val LOKAL_GEAENDERT = "lokal_geaendert"
    private const val VERZOEGERUNG_MS = 3000L

    private val hand = Handler(Looper.getMainLooper())
    private var hoerer: SharedPreferences.OnSharedPreferenceChangeListener? = null // stark halten
    @Volatile private var uebernehmeGerade = false

    /** Gehoert nicht zur Person, sondern zu diesem Geraet. */
    fun istGeraetebezogen(schluessel: String): Boolean {
        val k = schluessel.lowercase()
        return GERAET.any { k.contains(it) }
    }

    private val GERAET = listOf(
        "widget", "last_", "_last", "app_run_count", "was_", "tree_uri", "sd_card", "otg",
        "internal_storage", "save_recordings", "folder", "path", "uri", "app_id", "first_",
        "initial", "version", "sideload", "rate", "donat", "is_recording", "toggle_widget",
        "hint", "shown", "_count", "password", "pin", "token", "secret",
    )

    /**
     * Beim Start der App (Haupt-Activity) aufrufen. [uebernommen] laeuft auf dem
     * Haupt-Thread, wenn Werte aus der Nextcloud uebernommen wurden (z.B. recreate()).
     */
    fun abgleichen(ctx: Context, prefsName: String = "Prefs", uebernommen: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        beobachten(app, prefs)
        Thread {
            try {
                if (holen(app, prefs) && uebernommen != null) hand.post(uebernommen)
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich: $e")
            }
        }.start()
    }

    private fun datei(ctx: Context) = "${Hochladen.PERSOENLICH}/.rosenweg/${ctx.packageName}.json"

    private fun stand(ctx: Context) = ctx.getSharedPreferences(STAND, Context.MODE_PRIVATE)

    /** @return true, wenn Werte aus der Nextcloud uebernommen wurden */
    private fun holen(ctx: Context, prefs: SharedPreferences): Boolean {
        val dav = Ablage.webDav(ctx) ?: return false
        val pfad = datei(ctx)
        val lokal = stand(ctx).getLong(LOKAL_GEAENDERT, 0L)
        val tmp = java.io.File(ctx.cacheDir, "rosenweg-einstellungen.json")
        try {
            dav.herunterladen(pfad, tmp)
        } catch (e: NextcloudFehler) {
            if (e.code == 404) {
                senden(ctx, prefs) // noch nichts oben: die hiesigen hochladen
                return false
            }
            throw e
        }
        val json = JSONObject(tmp.readText())
        tmp.delete()
        val oben = json.optLong("geaendert", 0L)
        if (oben <= lokal) {
            if (oben < lokal) senden(ctx, prefs)
            return false
        }
        uebernehmen(prefs, json.optJSONObject("werte") ?: JSONObject())
        stand(ctx).edit().putLong(LOKAL_GEAENDERT, oben).apply()
        Log.i(TAG, "Einstellungen aus der Nextcloud uebernommen")
        return true
    }

    private fun uebernehmen(prefs: SharedPreferences, werte: JSONObject) {
        uebernehmeGerade = true
        try {
            val e = prefs.edit()
            // Was oben fehlt und zur Person gehoert, war dort entfernt bzw. Standard
            prefs.all.keys.filter { !istGeraetebezogen(it) && !werte.has(it) }.forEach { e.remove(it) }
            for (k in werte.keys()) {
                if (istGeraetebezogen(k)) continue
                val w = werte.getJSONObject(k)
                when (w.getString("t")) {
                    "b" -> e.putBoolean(k, w.getBoolean("v"))
                    "i" -> e.putInt(k, w.getInt("v"))
                    "l" -> e.putLong(k, w.getLong("v"))
                    "f" -> e.putFloat(k, w.getDouble("v").toFloat())
                    "s" -> e.putString(k, w.getString("v"))
                    "m" -> {
                        val a = w.getJSONArray("v")
                        e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            e.commit()
        } finally {
            uebernehmeGerade = false
        }
    }

    private fun beobachten(ctx: Context, prefs: SharedPreferences) {
        if (hoerer != null) return
        val senden = Runnable { Thread { senden(ctx, prefs) }.start() }
        hoerer = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (uebernehmeGerade || k == null || istGeraetebezogen(k)) return@OnSharedPreferenceChangeListener
            stand(ctx).edit().putLong(LOKAL_GEAENDERT, System.currentTimeMillis()).apply()
            hand.removeCallbacks(senden)
            hand.postDelayed(senden, VERZOEGERUNG_MS)
        }
        prefs.registerOnSharedPreferenceChangeListener(hoerer)
    }

    private fun senden(ctx: Context, prefs: SharedPreferences) {
        try {
            val dav = Ablage.webDav(ctx) ?: return
            var geaendert = stand(ctx).getLong(LOKAL_GEAENDERT, 0L)
            if (geaendert == 0L) {
                geaendert = System.currentTimeMillis()
                stand(ctx).edit().putLong(LOKAL_GEAENDERT, geaendert).apply()
            }
            val werte = JSONObject()
            for ((k, v) in prefs.all) {
                if (istGeraetebezogen(k) || v == null) continue
                werte.put(k, when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "m").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                })
            }
            val inhalt = JSONObject()
                .put("app", ctx.packageName)
                .put("geaendert", geaendert)
                .put("werte", werte)
                .toString(1).toByteArray()
            val pfad = datei(ctx)
            dav.ordnerSicherstellen(pfad.substringBeforeLast('/'))
            dav.hochladen(pfad, { inhalt.inputStream() }, inhalt.size.toLong(), "application/json")
        } catch (e: Exception) {
            Log.w(TAG, "Senden: $e") // naechste Aenderung oder naechster Start versucht es wieder
        }
    }
}
