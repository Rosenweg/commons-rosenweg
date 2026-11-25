package ch.rosenweg4303.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private const val NOTIF_ID = 4303
    private const val ARBEIT = "rosenweg-updater"

    /** Reihenfolge der Zustaendigkeit: die erste installierte dieser Apps prueft fuer alle. */
    val ZUSTAENDIG = listOf(
        "ch.rosenweg4303.messages", "ch.rosenweg4303.phone", "ch.rosenweg4303.filemanager",
        "ch.rosenweg4303.notes", "ch.rosenweg4303.gallery", "ch.rosenweg4303.camera",
        "ch.rosenweg4303.voicerecorder", "ch.rosenweg4303.paint", "ch.rosenweg4303.karten",
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
    fun zustaendig(c: Context): Boolean {
        if (geraetDa(c)) return false
        val erste = ZUSTAENDIG.firstOrNull { info(c, it) != null && gleicheSignatur(c, it) } ?: return false
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
            if (!zustaendig(applicationContext)) return Result.success()
            return try {
                val l = verfuegbar(applicationContext)
                if (l.isNotEmpty()) melden(applicationContext, l) else abmelden(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "Pruefung: ${e.javaClass.simpleName}")
                Result.retry()
            }
        }
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

    internal fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    internal fun signaturen(p: PackageInfo?): Set<String> {
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
            if (!r.isSuccessful) throw java.io.IOException("Katalog ${r.code}")
            r.body?.string().orEmpty()
        }
        val j = JSONObject(text)
        val eigen = eigeneSignatur(c)
        // Der Katalog nennt die Signatur der Rosenweg-Apps: muss unsere sein, sonst nichts tun
        if (j.optString("signatur_sha256").lowercase() !in eigen) {
            Log.w(TAG, "Katalog-Signatur passt nicht zu dieser App")
            return emptyList()
        }
        val apps = j.getJSONArray("apps")
        val l = mutableListOf<Update>()
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            val paket = a.optString("paket")
            val inst = info(c, paket) ?: continue
            if (signaturen(inst).intersect(eigen).isEmpty()) continue   // fremd signiert: nicht anfassen
            val neu = a.optLong("version_code", 0)
            if (neu > versionCode(inst)) {
                l += Update(paket, a.optString("name"), versionCode(inst), neu, a.optString("version_name"),
                    a.optString("sha256").lowercase(), a.optLong("groesse"), BASIS + a.optString("url"))
            }
        }
        return l
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
            if (!r.isSuccessful) throw java.io.IOException("Laden ${r.code}")
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
                f.delete(); throw SecurityException("Prüfsumme stimmt nicht")
            }
        }
        val p = if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") c.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        if (p == null || p.packageName != u.paket || versionCode(p) != u.neu ||
            signaturen(p).intersect(eigeneSignatur(c)).isEmpty()) {
            f.delete(); throw SecurityException("APK passt nicht (Paket/Version/Signatur)")
        }
        return f
    }
}
