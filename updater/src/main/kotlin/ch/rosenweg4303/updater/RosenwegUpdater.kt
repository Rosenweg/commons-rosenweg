package ch.rosenweg4303.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Rosenweg Updater (Stefan 25.11.2026): Rosenweg-Apps auf Handys OHNE Rosenweg Geraet aktuell
 * halten. Auf geteilten Telefonen installiert Geraet selbst aus dem Katalog - dort tut der
 * Updater nichts.
 *
 * Quelle: https://pbx.rosenweg4303.ch/api/apps/index.json (oeffentlich, nur Rosenweg-signierte
 * APKs). Genau EINE installierte Rosenweg-App ist zustaendig (ZUSTAENDIG, die erste installierte),
 * damit nicht jede App einzeln meldet; sie aktualisiert alle installierten Rosenweg-Apps, auch
 * die ohne Internet-Recht (Tastatur, Rechner, ...).
 *
 * Jede APK wird vor dem Installieren geprueft: Groesse, SHA-256 aus dem Katalog, Paketname,
 * Version und vor allem die Signatur = die eigene (Rosenweg-Schluessel). Installiert wird ueber den
 * Android-Installer; Android laesst jede Installation bestaetigen.
 */
object RosenwegUpdater {
    internal const val TAG = "RosenwegUpdater"
    const val KATALOG = "https://pbx.rosenweg4303.ch/api/apps/index.json"
    private const val BASIS = "https://pbx.rosenweg4303.ch"
    private const val KANAL = "rosenweg_updates"
    // 0.1.6: eigene Nummer. 4303 nimmt auch Rosenweg Messages fuer den Vordergrunddienst
    // (MessengerVerbindung): beide Meldungen ersetzten sich gegenseitig.
    internal const val NOTIF_ID = 0x52_57_55_50 // "RWUP"
    /** Versionsstand an die Anlage (NOC), ohne Personendaten. */
    const val STAND = "https://pbx.rosenweg4303.ch/api/apps/stand"
    private const val ARBEIT = "rosenweg-updater"

    /**
     * Reihenfolge der Zustaendigkeit: die erste installierte dieser Apps (mit Updater) prueft fuer
     * alle. Seit 25.11.2026 haben ALLE Rosenweg-Apps den Updater (Stefan: Weg 1).
     */
    val ZUSTAENDIG = listOf(
        "ch.rosenweg4303.messages", "ch.rosenweg4303.phone", "ch.rosenweg4303.filemanager",
        "ch.rosenweg4303.notes", "ch.rosenweg4303.gallery", "ch.rosenweg4303.camera",
        "ch.rosenweg4303.voicerecorder", "ch.rosenweg4303.paint", "ch.rosenweg4303.karten",
        "ch.rosenweg4303.documents", "ch.rosenweg4303.calendar", "ch.rosenweg4303.contacts",
        "ch.rosenweg4303.keyboard", "ch.rosenweg4303.home", "ch.rosenweg4303.clock", "ch.rosenweg4303.math",
        // 0.1.4: Kiosk (Android TV, meist die einzige Rosenweg-App dort) und die Flutter-Apps
        // (rosenweg_basis). Immer HINTEN anhaengen: aeltere Apps kennen die neuen Namen nicht und
        // muessen trotzdem zur selben Wahl kommen.
        "ch.rosenweg4303.kiosk", "ch.rosenweg4303.vm", "ch.rosenweg4303.salz", "ch.rosenweg4303.profil",
        // Rosenweg Mein Telefon (Flutter)
        "ch.rosenweg4303.meintelefon",
    )

    internal val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Update(val paket: String, val name: String, val installiert: Long, val neu: Long,
                      val versionName: String, val sha256: String, val groesse: Long, val url: String)

    /** Rosenweg Geraet da (geteiltes Telefon)? Dann installiert Geraet, nicht wir. */
    fun geraetDa(c: Context): Boolean = try {
        c.packageManager.resolveContentProvider("ch.rosenweg4303.telefon.anlage", 0) != null
    } catch (e: Exception) {
        false
    }

    /** Bin ich (diese App) die zustaendige? */
    fun zustaendig(c: Context): Boolean = !geraetDa(c) && meldetStand(c)

    /** Die zustaendige App ohne Blick auf Rosenweg Geraet: sie meldet den Versionsstand (0.1.6). */
    internal fun meldetStand(c: Context): Boolean {
        // nur Apps, die den Updater schon enthalten (alte Versionen ohne ihn pruefen nie)
        val erste = ZUSTAENDIG.firstOrNull { info(c, it) != null && gleicheSignatur(c, it) && hatUpdater(c, it) }
        // Eine App, die (noch) nicht in der Liste steht, prueft selbst, solange keine aus der Liste
        // installiert ist - sonst bliebe sie ohne Updates (so ging es Rosenweg Kiosk bis 0.1.3).
        if (erste == null) return c.packageName !in ZUSTAENDIG
        return erste == c.packageName
    }

    /** Beim Start jeder App mit dem Modul aufrufen: taeglich pruefen (nur die zustaendige meldet). */
    fun einrichten(c: Context) {
        val app = c.applicationContext
        val req = PeriodicWorkRequestBuilder<Pruefung>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(ARBEIT, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Pruefung(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            val c = applicationContext
            if (!meldetStand(c)) return Result.success()
            if (geraetDa(c)) {
                // geteiltes Telefon: Geraet installiert; hier nur den Stand melden (NOC)
                standMelden(c, null)
                return Result.success()
            }
            return try {
                val l = verfuegbar(c)
                if (l.isNotEmpty()) melden(c, l) else abmelden(c)
                Result.success()
            } catch (e: UpdaterFehler) {
                Log.w(TAG, "Pruefung: ${e.message}")
                if (e.dauerhaft) Result.success() else Result.retry()
            } catch (e: Exception) {
                Log.w(TAG, "Pruefung: ${e.javaClass.simpleName}")
                Result.retry()
            }
        }
    }

    /**
     * "Nach Updates suchen" (0.1.6): sofort pruefen und das Ergebnis zeigen - "Alle aktuell",
     * "Neue Fassung X - Installieren" oder der Fehler im Klartext. Fuer den Knopf in den
     * Einstellungen jeder App; geht aus jeder App mit dem Modul, auch wenn sie nicht die
     * zustaendige ist. Auf geteilten Telefonen sagt die Seite, dass Rosenweg Geraet aktualisiert.
     */
    @JvmStatic
    fun jetztPruefen(c: Context) {
        val i = Intent(c, UpdateActivity::class.java)
        if (c !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    /** Fehler mit Text fuer die Person; dauerhaft = Wiederholen hilft nicht. */
    class UpdaterFehler(text: String, val dauerhaft: Boolean = false, cause: Throwable? = null) : Exception(text, cause)

    /** Technischer Fehler -> Satz fuer die Person. */
    fun klartext(e: Throwable): String = when (e) {
        is UpdaterFehler -> e.message.orEmpty()
        is java.net.UnknownHostException -> "Keine Verbindung zum Rosenweg-Server. Ist das Handy mit dem Internet verbunden?"
        is java.net.SocketTimeoutException -> "Der Rosenweg-Server antwortet nicht. Bitte später nochmals versuchen."
        is javax.net.ssl.SSLException -> "Die sichere Verbindung zum Rosenweg-Server ist fehlgeschlagen (Datum und Uhrzeit richtig?)."
        is java.io.IOException -> "Keine Verbindung zum Rosenweg-Server (${e.message ?: e.javaClass.simpleName})."
        is org.json.JSONException -> "Der Rosenweg-Server hat eine unlesbare Antwort geschickt. Bitte später nochmals versuchen."
        is SecurityException -> e.message ?: "Die geladene Datei ist nicht vertrauenswürdig und wurde gelöscht."
        else -> "Unerwarteter Fehler: ${e.javaClass.simpleName}${e.message?.let { " – $it" }.orEmpty()}"
    }

    internal fun info(c: Context, paket: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageInfo(paket, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(paket, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun hatUpdater(c: Context, paket: String): Boolean = try {
        val p = c.packageManager.getPackageInfo(paket, PackageManager.GET_PROVIDERS)
        p.providers?.any { it.name == UpdaterStart::class.java.name } == true
    } catch (e: Exception) {
        false
    }

    internal fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    // signingInfo gibt es erst ab Android 9 (API 28). Darunter keine Signaturen: der Updater
    // fasst dann keine App an (statt mit NoSuchFieldError abzustuerzen).
    internal fun signaturen(p: PackageInfo?): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val si = p?.signingInfo ?: return emptySet()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return certs.map { sha256(it.toByteArray()) }.toSet()
    }

    internal fun eigeneSignatur(c: Context): Set<String> = signaturen(info(c, c.packageName))

    private fun gleicheSignatur(c: Context, paket: String): Boolean =
        signaturen(info(c, paket)).intersect(eigeneSignatur(c)).isNotEmpty()

    internal fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Updates fuer installierte Rosenweg-Apps (blockierend). */
    fun verfuegbar(c: Context): List<Update> {
        val text = http.newCall(Request.Builder().url(KATALOG).build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdaterFehler("Der Rosenweg-Server hat die Liste der Apps nicht geliefert (Fehler ${r.code}).")
            r.body?.string().orEmpty()
        }
        val j = JSONObject(text)
        val eigen = eigeneSignatur(c)
        // Der Katalog nennt die Signatur der Rosenweg-Apps: muss unsere sein, sonst nichts tun
        if (j.optString("signatur_sha256").lowercase() !in eigen) {
            Log.w(TAG, "Katalog-Signatur passt nicht zu dieser App")
            throw UpdaterFehler("Diese Fassung ist nicht mit dem Rosenweg-Schlüssel signiert (Entwicklungsfassung). " +
                "Updates gibt es nur für Fassungen von rosenweg4303.ch/apps.", dauerhaft = true)
        }
        val apps = j.getJSONArray("apps")
        val l = mutableListOf<Update>()
        val pakete = mutableSetOf<String>()
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            val paket = a.optString("paket")
            val inst = info(c, paket) ?: continue
            if (signaturen(inst).intersect(eigen).isEmpty()) continue   // fremd signiert: nicht anfassen
            pakete += paket
            val neu = a.optLong("version_code", 0)
            if (neu > versionCode(inst)) {
                l += Update(paket, a.optString("name"), versionCode(inst), neu, a.optString("version_name"),
                    a.optString("sha256").lowercase(), a.optLong("groesse"), BASIS + a.optString("url"))
            }
        }
        standMelden(c, pakete)
        return l
    }

    /** Geraet-Kennung fuer das NOC: SHA-256 der ANDROID_ID (gleich fuer alle Rosenweg-Apps, da
     * gleicher Schluessel), mit festem Praefix. Keine Personendaten, nicht umkehrbar. */
    internal fun geraetKennung(c: Context): String {
        @android.annotation.SuppressLint("HardwareIds")
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return sha256("rosenweg-geraet:$id".toByteArray())
    }

    /** Inhalt der Meldung an [STAND] (testbar ohne Android). */
    internal fun standJson(geraet: String, geteilt: Boolean, sdk: Int, apps: List<Triple<String, String, Long>>): String =
        JSONObject()
            .put("geraet", geraet)
            .put("plattform", "android")
            .put("geteilt", geteilt)
            .put("system", "Android SDK $sdk")
            .put("apps", JSONArray().apply {
                apps.forEach { (p, n, v) -> put(JSONObject().put("paket", p).put("version", n).put("version_code", v)) }
            }).toString()

    /**
     * Versionsstand an die Anlage melden (beim Updater-Lauf, nur die zustaendige App, oder bei
     * "Nach Updates suchen"): Geraet (gehasht), Paket, Version, Plattform. [pakete] = schon
     * bekannte installierte Rosenweg-Apps, sonst die aus ZUSTAENDIG. Fehler bleiben still.
     */
    internal fun standMelden(c: Context, pakete: Set<String>?) {
        try {
            val eigen = eigeneSignatur(c)
            val kandidaten = (pakete ?: emptySet()) + ZUSTAENDIG + c.packageName
            val apps = kandidaten.mapNotNull { p ->
                val i = info(c, p) ?: return@mapNotNull null
                if (signaturen(i).intersect(eigen).isEmpty()) return@mapNotNull null
                Triple(p, i.versionName.orEmpty(), versionCode(i))
            }.sortedBy { it.first }
            if (apps.isEmpty()) return
            val body = standJson(geraetKennung(c), geraetDa(c), Build.VERSION.SDK_INT, apps)
                .toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(STAND).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) Log.i(TAG, "Stand melden: ${r.code}")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Stand melden: ${e.javaClass.simpleName}")
        }
    }

    private fun melden(c: Context, l: List<Update>) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(KANAL, "Rosenweg-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 0, Intent(c, UpdateActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = l.joinToString(", ") { it.name.removePrefix("Rosenweg ") }
        val n = android.app.Notification.Builder(c, KANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (l.size == 1) "Update für ${l[0].name}" else "${l.size} Rosenweg-Apps haben Updates")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.i(TAG, "Benachrichtigung nicht erlaubt")
        }
    }

    internal fun abmelden(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** APK laden und pruefen (blockierend). Gibt die geprueften Datei zurueck oder wirft. */
    internal fun laden(c: Context, u: Update): File {
        val f = File(c.cacheDir, "rosenweg-update-${u.paket}.apk")
        http.newCall(Request.Builder().url(u.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdaterFehler("Die neue Fassung ist gerade nicht erhältlich (Fehler ${r.code}).")
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            r.body!!.byteStream().use { inp ->
                f.outputStream().use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val k = inp.read(b)
                        if (k < 0) break
                        md.update(b, 0, k); out.write(b, 0, k); n += k
                    }
                }
            }
            val ist = md.digest().joinToString("") { "%02x".format(it) }
            if (ist != u.sha256 || (u.groesse > 0 && n != u.groesse)) {
                f.delete(); throw SecurityException("Die geladene Datei ist beschädigt (Prüfsumme stimmt nicht) und wurde gelöscht.")
            }
        }
        val p = if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") c.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        if (p == null || p.packageName != u.paket || versionCode(p) != u.neu ||
            signaturen(p).intersect(eigeneSignatur(c)).isEmpty()) {
            f.delete(); throw SecurityException("Die geladene Datei passt nicht zur App (Paket, Version oder Signatur) und wurde gelöscht.")
        }
        return f
    }
}
