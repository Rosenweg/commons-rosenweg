package ch.rosenweg4303.cloud

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Was eine App in den persoenlichen Ordner hochgeladen hat, wieder anzeigen:
 * Persönlich/<appOrdner>/... (z.B. Diktiergerät/2026-11/x.m4a).
 *
 * Alle Aufrufe mit Netz blockieren — im Hintergrund aufrufen.
 */
object Ablage {
    /** Zugang dieser App, oder null ohne Anmeldung. */
    fun webDav(ctx: Context): WebDav? = KontoSpeicher(ctx).laden()?.let {
        WebDav(it, "${ctx.applicationInfo.loadLabel(ctx.packageManager)} (Android)")
    }

    fun ordner(appOrdner: String) = "${Hochladen.PERSOENLICH}/$appOrdner"

    /** Alle Dateien der App in der Nextcloud; null ohne Anmeldung oder ohne Netz. */
    fun dateien(ctx: Context, appOrdner: String): List<Eintrag>? = try {
        webDav(ctx)?.alleDateien(ordner(appOrdner))
    } catch (e: Exception) {
        null
    }

    /** Neueste Datei der App in der Nextcloud (nach Aenderungszeit); null ohne Anmeldung/Netz/Dateien. */
    fun neueste(ctx: Context, appOrdner: String): Eintrag? = dateien(ctx, appOrdner)?.maxByOrNull { it.geaendert }

    /** Vorschaubild-Adresse und Kopfzeilen (z.B. fuer Glide mit LazyHeaders). */
    fun vorschau(ctx: Context, e: Eintrag, px: Int = 256): Pair<String, Map<String, String>>? {
        val dav = webDav(ctx) ?: return null
        return dav.vorschauAdresse(e, px) to dav.abspielKopfzeilen()
    }

    /** Adresse und Kopfzeilen zum Streamen einer Datei (MediaPlayer, ExoPlayer ...). */
    fun streamen(ctx: Context, pfad: String): Pair<Uri, Map<String, String>>? {
        val dav = webDav(ctx) ?: return null
        return Uri.parse(dav.url(pfad).toString()) to dav.abspielKopfzeilen()
    }

    /** Lokale Kopie im Cache (zum Teilen/Oeffnen mit anderen Apps). Blockiert. */
    fun lokaleKopie(ctx: Context, pfad: String): File {
        val dav = webDav(ctx) ?: throw NextcloudFehler(401, "Nicht angemeldet")
        val ziel = File(File(ctx.cacheDir, "rosenweg-ablage"), pfad.substringAfterLast('/'))
        dav.herunterladen(pfad, ziel) // immer frisch: die Datei kann inzwischen geaendert sein
        return ziel
    }
}
