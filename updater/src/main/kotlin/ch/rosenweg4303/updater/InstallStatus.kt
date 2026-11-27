package ch.rosenweg4303.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Ergebnis des Android-Installers: Bestaetigung anzeigen oder die naechste App laden. */
class InstallStatus : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val paket = i.getStringExtra("paket").orEmpty()
        val session = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val bestaetigen = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                if (session != UpdateActivity.aktuelleSession) {
                    // alte Session (z.B. doppelt gestartet): verwerfen statt einen zweiten Dialog zu zeigen
                    Log.i(RosenwegUpdater.TAG, "$paket: Session $session ist nicht die laufende, verworfen")
                    try { c.packageManager.packageInstaller.abandonSession(session) } catch (e: Exception) { /* schon weg */ }
                    return
                }
                bestaetigen?.let { UpdateActivity.bestaetigungOffen(c, it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(RosenwegUpdater.TAG, "$paket aktualisiert (Session $session)")
                UpdateActivity.ergebnis(c, session, true, null)
            }
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(RosenwegUpdater.TAG, "$paket: Status $st $m (Session $session)")
                UpdateActivity.ergebnis(c, session, false, fehlerText(st, m))
            }
        }
    }

    companion object {
        const val PLAY_PROTECT = "Google Play Protect hat die Installation verhindert. Beim nächsten Versuch im " +
            "Fenster von Play Protect „Mehr Details“ › „Trotzdem installieren“ wählen."

        /** Status des Android-Installers -> Satz fuer die Person (0.1.8, Befund A37: Salz wurde von
         * Play Protect abgelehnt, Status 3 mit INSTALL_FAILED_VERIFICATION_FAILURE). */
        fun fehlerText(status: Int, meldung: String?): String {
            val m = meldung.orEmpty()
            return when {
                m.contains("VERIFICATION", ignoreCase = true) || status == PackageInstaller.STATUS_FAILURE_BLOCKED -> PLAY_PROTECT
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Abgebrochen"
                status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Zu wenig Speicher auf dem Handy"
                status == PackageInstaller.STATUS_FAILURE_CONFLICT -> "Passt nicht zur installierten Fassung (Konflikt)"
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Passt nicht zu diesem Handy"
                else -> m.ifEmpty { "Nicht installiert (Status $status)" }
            }
        }
    }
}
