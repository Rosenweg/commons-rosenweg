package ch.rosenweg4303.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Kennzeichnet eine App mit dem Rosenweg Updater (0.1.8): andere Rosenweg-Apps finden sie ueber
 * [RosenwegUpdater.AKTION_APP], ohne dass ihr Paketname in einer Liste stehen muss. Tut nichts.
 */
class RosenwegAppKennung : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Unit
}
