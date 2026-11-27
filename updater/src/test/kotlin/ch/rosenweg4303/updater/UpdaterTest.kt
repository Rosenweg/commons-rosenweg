package ch.rosenweg4303.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun eigeneMeldungsnummer() {
        // 4303 = Vordergrunddienst von Rosenweg Messages (MessengerVerbindung)
        assertNotEquals(4303, RosenwegUpdater.NOTIF_ID)
    }

    @Test
    fun fehlerImKlartext() {
        assertTrue(RosenwegUpdater.klartext(java.net.UnknownHostException("pbx.rosenweg4303.ch")).contains("Internet"))
        assertTrue(RosenwegUpdater.klartext(java.net.SocketTimeoutException()).contains("antwortet nicht"))
        assertEquals("Text", RosenwegUpdater.klartext(RosenwegUpdater.UpdaterFehler("Text", dauerhaft = true)))
        assertTrue(RosenwegUpdater.klartext(IllegalStateException("x")).startsWith("Unerwarteter Fehler"))
    }

    @Test
    fun geraetKennungIstHash() {
        val h = RosenwegUpdater.sha256("rosenweg-geraet:abc".toByteArray())
        assertTrue(Regex("[0-9a-f]{64}").matches(h))
    }

    @Test
    fun playProtectErkannt() {
        assertEquals(InstallStatus.PLAY_PROTECT, InstallStatus.fehlerText(3, "INSTALL_FAILED_VERIFICATION_FAILURE: Verification failed"))
        assertEquals("Abgebrochen", InstallStatus.fehlerText(3, null))
        assertEquals(InstallStatus.PLAY_PROTECT, InstallStatus.fehlerText(2, null))
    }
}
