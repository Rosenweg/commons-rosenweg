package ch.rosenweg4303.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Upload-Warteschlange fuer die Rosenweg-Apps (Kamera, Diktiergeraet, Notizen,
 * Paint ...). Jede App laedt selbst hoch — keine andere App ist Voraussetzung.
 *
 * - [einreihen] merkt sich die Datei (in files/rosenweg-hochladen, ueberlebt
 *   Neustarts) und stoesst WorkManager an. Hochgeladen wird, sobald Netz da ist.
 * - Nie ueberschreiben: gibt es den Namen schon, wird "(1)" usw. angehaengt.
 * - Grundregel: lokal geloescht ([lokalLoeschen]) wird erst, wenn die
 *   Nextcloud den Empfang bestaetigt hat.
 * - Ohne Anmeldung bleibt alles liegen und geht nach dem Anmelden los.
 */
object Hochladen {
    /** Persoenlicher Ordner der Person (AD-Home) in der Rosenweg-Nextcloud. */
    const val PERSOENLICH = "Persönlich"

    private const val ORDNER = "rosenweg-hochladen"
    private const val ARBEIT = "rosenweg-hochladen"
    private const val TAG = "RosenwegHochladen"
    private const val MAX_VERSUCHE = 5

    /** Standardziel: Persönlich/<app>/<yyyy-MM>/<name>. */
    fun ziel(appOrdner: String, name: String, zeitMs: Long = System.currentTimeMillis()): String {
        val monat = SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date(zeitMs))
        return "$PERSOENLICH/$appOrdner/$monat/$name"
    }

    fun einreihen(ctx: Context, datei: File, ziel: String, lokalLoeschen: Boolean, mime: String? = null) =
        auftrag(ctx, "datei", datei.absolutePath, ziel, lokalLoeschen, mime)

    /** Fuer Dateien, die die App selbst im MediaStore angelegt hat. */
    fun einreihen(ctx: Context, uri: Uri, ziel: String, lokalLoeschen: Boolean, mime: String? = null) =
        auftrag(ctx, "uri", uri.toString(), ziel, lokalLoeschen, mime)

    /** Noch nicht hochgeladen (ohne die mit dauerhaftem Fehler). */
    fun ausstehend(ctx: Context) = auftraege(ctx).count { it.second.getProperty("fehler") == null }

    /** Fehlertexte der fehlgeschlagenen Auftraege, z.B. "Diktiergerät/…/x.m4a: 403 Keine Berechtigung". */
    fun fehlerTexte(ctx: Context): List<String> = auftraege(ctx).mapNotNull { (_, p) ->
        p.getProperty("fehler")?.let { "${p.getProperty("ziel").removePrefix("$PERSOENLICH/")}: $it" }
    }

    /** Mit dauerhaftem Fehler (z.B. Speicher voll) — Datei bleibt lokal. */
    fun fehlgeschlagen(ctx: Context) = auftraege(ctx).count { it.second.getProperty("fehler") != null }

    /** Nach dem Anmelden oder auf Wunsch: Warteschlange abarbeiten (inkl. fehlgeschlagener). */
    fun anstossen(ctx: Context, fehlerNochmal: Boolean = false) {
        if (fehlerNochmal) {
            auftraege(ctx).filter { it.second.getProperty("fehler") != null }.forEach { (f, p) ->
                p.remove("fehler")
                p.remove("versuche")
                speichern(f, p)
            }
        }
        val arbeit = OneTimeWorkRequestBuilder<HochladenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(ARBEIT, ExistingWorkPolicy.APPEND_OR_REPLACE, arbeit)
    }

    private fun auftrag(ctx: Context, art: String, quelle: String, ziel: String, loeschen: Boolean, mime: String?) {
        require(ziel.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Ungueltiges Ziel: $ziel" }
        val dir = File(ctx.filesDir, ORDNER).apply { mkdirs() }
        val p = Properties()
        p["art"] = art
        p["quelle"] = quelle
        p["ziel"] = ziel
        p["loeschen"] = loeschen.toString()
        mime?.let { p["mime"] = it }
        speichern(File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.properties"), p)
        anstossen(ctx)
    }

    /** Laeuft im Worker. true = fertig, false = spaeter nochmal (Netz, Ueberlastung). */
    internal fun abarbeiten(ctx: Context): Boolean {
        val konto = KontoSpeicher(ctx).laden() ?: return true // wartet auf Anmeldung
        val dav = WebDav(konto, appName(ctx))
        for ((f, p) in auftraege(ctx).filter { it.second.getProperty("fehler") == null }) {
            try {
                einen(ctx, dav, p)
                f.delete()
            } catch (e: NextcloudFehler) {
                when {
                    e.anmeldungNoetig -> return true // App-Passwort weg: nach neuer Anmeldung weiter
                    e.voruebergehend || e.code == 0 -> return false
                    else -> {
                        // 403 (keine Rechte) und 507 (Speicher voll) sofort aufgeben, alles andere
                        // bis zu MAX_VERSUCHE Mal mit Pause. Die Datei bleibt immer lokal.
                        Log.w(TAG, "Hochladen ${p.getProperty("ziel")}: ${e.code} ${e.message}")
                        val versuche = (p.getProperty("versuche")?.toIntOrNull() ?: 0) + 1
                        p["versuche"] = versuche.toString()
                        if (e.code == 403 || e.code == 507 || versuche >= MAX_VERSUCHE) {
                            p["fehler"] = "${e.code} ${e.message}"
                            speichern(f, p)
                        } else {
                            speichern(f, p)
                            return false
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Hochladen ${p.getProperty("ziel")}: $e")
                return false
            }
        }
        return true
    }

    private fun einen(ctx: Context, dav: WebDav, p: Properties) {
        val art = p.getProperty("art")
        val quelle = p.getProperty("quelle")
        val ziel = p.getProperty("ziel")
        val mime = p.getProperty("mime")
        val datei = if (art == "datei") File(quelle) else null
        val uri = if (art == "uri") Uri.parse(quelle) else null

        val laenge: Long
        val oeffnen: () -> InputStream
        if (datei != null) {
            if (!datei.exists()) return // inzwischen geloescht -> nichts zu tun
            laenge = datei.length()
            oeffnen = { datei.inputStream() }
        } else {
            laenge = ctx.contentResolver.openFileDescriptor(uri!!, "r")?.use { it.statSize } ?: return
            oeffnen = { ctx.contentResolver.openInputStream(uri) ?: throw IOException("Quelle weg: $uri") }
        }
        val geaendert = datei?.lastModified()

        val ordner = ziel.substringBeforeLast('/', "")
        if (ordner.isNotEmpty()) dav.ordnerSicherstellen(ordner)

        val name = ziel.substringAfterLast('/')
        val basis = name.substringBeforeLast('.', name)
        val endung = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var versuch = 0
        while (true) {
            val kandidat = if (versuch == 0) ziel else "$ordner/$basis ($versuch)$endung".trimStart('/')
            try {
                dav.hochladen(kandidat, oeffnen, laenge, mime, geaendert, nurNeu = true)
                break
            } catch (e: NextcloudFehler) {
                if (e.code != 412 || versuch >= 50) throw e
                versuch++ // Name schon vergeben
            }
        }

        // erst jetzt: Nextcloud hat bestaetigt (Grundregel)
        if (p.getProperty("loeschen") == "true") {
            if (datei != null) {
                datei.delete()
            } else if (DocumentsContract.isDocumentUri(ctx, uri)) {
                DocumentsContract.deleteDocument(ctx.contentResolver, uri!!)
            } else {
                ctx.contentResolver.delete(uri!!, null, null)
            }
        }
    }

    private fun appName(ctx: Context) = "${ctx.applicationInfo.loadLabel(ctx.packageManager)} (Android)"

    private fun auftraege(ctx: Context): List<Pair<File, Properties>> =
        (File(ctx.filesDir, ORDNER).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".properties") }
            .sortedBy { it.name }
            .mapNotNull { f ->
                try {
                    f to Properties().apply { f.inputStream().use { load(it) } }
                } catch (e: IOException) {
                    null
                }
            }

    private fun speichern(f: File, p: Properties) {
        val tmp = File(f.parentFile, f.name + ".neu")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(f)
    }
}

class HochladenWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = if (Hochladen.abarbeiten(applicationContext)) Result.success() else Result.retry()
}
