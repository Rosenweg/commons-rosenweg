package ch.rosenweg4303.cloud.dav

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.NextcloudFehler
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Hintergrund-Abgleich mit der Rosenweg-Nextcloud (Kalender, Kontakte) ueber WorkManager:
 *  - periodisch alle 15 Minuten (nur mit Netz und nicht bei knappem Akku),
 *  - kurz nach lokalen Aenderungen: Ausloeser auf die Inhalte des Providers (content-URI-Trigger,
 *    laeuft auch, wenn die App zu ist). Vor dem Netz wird nur lokal geprueft, ob ueberhaupt etwas
 *    hochzuladen ist - die eigenen Schreibvorgaenge des Abgleichs loesen also keinen neuen aus,
 *  - sofort (beim Oeffnen der App, nach der Anmeldung).
 * Der Abgleich selbst ist inkrementell (WebDAV-Sync), ein Lauf ohne Aenderungen kostet eine Anfrage.
 *
 * Die App leitet eine Klasse ab (WorkManager erzeugt sie ueber den Namen) und plant mit [DavPlaner].
 */
abstract class DavArbeit(c: Context, p: WorkerParameters) : Worker(c, p) {
    /** Abgleichen. true = es hat sich etwas geaendert (App neu einlesen). */
    protected abstract fun abgleichen(konto: Konto): Boolean

    /** Kein Konto (abgemeldet): lokale Nextcloud-Daten entfernen. */
    protected open fun abgemeldet() {}

    /** Gibt es lokal etwas hochzuladen? (ohne Netz) */
    protected abstract fun lokalGeaendert(): Boolean

    /** Hat die App die Berechtigungen (Kalender/Kontakte lesen und schreiben)? */
    protected abstract fun berechtigt(): Boolean

    /** Der Plan, zu dem diese Arbeit gehoert (Name, Ausloeser-URIs). */
    protected abstract val plan: DavPlaner.Plan

    override fun doWork(): Result {
        val ausloeser = inputData.getBoolean(AUSLOESER, false)
        try {
            if (!berechtigt()) return Result.success()
            if (ausloeser && !lokalGeaendert()) return Result.success()
            exklusiv(plan.name) {
                val konto = KontoSpeicher(applicationContext).laden()
                if (konto == null) abgemeldet() else abgleichen(konto)
            }
            return Result.success()
        } catch (e: NextcloudFehler) {
            Log.w(TAG, "${plan.name}: ${e.code} ${e.message}")
            return if (e.anmeldungNoetig || runAttemptCount >= 3) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Log.w(TAG, "${plan.name}: ${e.javaClass.simpleName} ${e.message}")
            return if (runAttemptCount >= 3) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "${plan.name}: ${e.javaClass.simpleName} ${e.message}")
            return Result.failure()
        } finally {
            // Ausloeser gelten nur einmal: gleich wieder scharf machen
            if (ausloeser) DavPlaner.ausloeser(applicationContext, plan, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    companion object {
        private const val TAG = "RosenwegDav"
        internal const val AUSLOESER = "ausloeser"
        private val sperren = ConcurrentHashMap<String, Any>()

        /** Nie zwei Abgleiche derselben Art gleichzeitig (Arbeit und App teilen sich das). */
        fun <T> exklusiv(name: String, block: () -> T): T = synchronized(sperren.getOrPut(name) { Any() }) { block() }
    }
}

/** Plant den Hintergrund-Abgleich (siehe [DavArbeit]). */
object DavPlaner {
    /** [name] eindeutig je App und Art, [klasse] die abgeleitete [DavArbeit], [uris] die Provider-Inhalte. */
    data class Plan(val name: String, val klasse: Class<out DavArbeit>, val uris: List<Uri>)

    private fun netz(akku: Boolean = false) = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(akku)

    /** Periodisch + Ausloeser einrichten (mehrfach aufrufen schadet nicht). */
    fun einrichten(c: Context, p: Plan) {
        val wm = WorkManager.getInstance(c.applicationContext)
        wm.enqueueUniquePeriodicWork("${p.name}-periodisch", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(p.klasse, 15, TimeUnit.MINUTES).setConstraints(netz(akku = true).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        ausloeser(c, p, ExistingWorkPolicy.KEEP)
    }

    internal fun ausloeser(c: Context, p: Plan, regel: ExistingWorkPolicy) {
        val bed = netz()
        p.uris.forEach { bed.addContentUriTrigger(it, true) }
        bed.setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
        bed.setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("${p.name}-ausloeser", regel,
            OneTimeWorkRequest.Builder(p.klasse).setConstraints(bed.build())
                .setInputData(Data.Builder().putBoolean(DavArbeit.AUSLOESER, true).build()).build())
    }

    /** Gleich abgleichen (sobald Netz da ist). */
    fun sofort(c: Context, p: Plan) {
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("${p.name}-sofort", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(p.klasse).setConstraints(netz().build()).build())
    }

    /** Abgemeldet: nichts mehr planen. */
    fun abschalten(c: Context, p: Plan) {
        val wm = WorkManager.getInstance(c.applicationContext)
        listOf("periodisch", "ausloeser", "sofort").forEach { wm.cancelUniqueWork("${p.name}-$it") }
    }
}
