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
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val bestaetigen = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                bestaetigen?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(RosenwegUpdater.TAG, "$paket aktualisiert")
                UpdateActivity.aktiv?.fertig(paket, true, null)
            }
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(RosenwegUpdater.TAG, "$paket: Status $st $m")
                UpdateActivity.aktiv?.fertig(paket, false, if (st == PackageInstaller.STATUS_FAILURE_ABORTED) "abgebrochen" else m)
            }
        }
    }
}
