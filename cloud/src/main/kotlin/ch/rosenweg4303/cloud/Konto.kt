package ch.rosenweg4303.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Die Nextcloud der Siedlung. Andere Server lassen die Apps nicht zu. */
const val ROSENWEG_NEXTCLOUD = "https://nextcloud.rosenweg4303.ch"

/**
 * Zugang einer Person zur Nextcloud: App-Passwort aus dem Login-Flow, nie das
 * eigentliche Passwort (Anmeldung laeuft ueber Authentik im Browser).
 */
data class Konto(
    val server: String,
    val benutzer: String,
    val appPasswort: String,
    /** Zugang kommt von Rosenweg Gerät (geteiltes Telefon): gilt fuer alle Apps, Abmelden dort. */
    val vomGeraet: Boolean = false,
) {
    override fun toString() = "Konto($benutzer@$server)" // Passwort nie in Logs
}

/**
 * Speichert das Konto app-privat; das App-Passwort ist mit einem Schluessel aus
 * dem Android-Keystore verschluesselt (verlaesst das Geraet nicht, faellt beim
 * Loeschen der App-Daten weg — auf geteilten Telefonen also beim Abmelden).
 *
 * Auf geteilten Telefonen liefert Rosenweg Gerät den Zugang der angemeldeten Person
 * (App-Passwort von der Anlage, Signatur-geschuetzt) — das hat Vorrang. Ohne Rosenweg
 * Gerät (privates Handy) meldet sich jede App selbst an.
 */
class KontoSpeicher(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("rosenweg_cloud", Context.MODE_PRIVATE)

    fun laden(): Konto? = vomGeraet() ?: eigenes()

    /** Zugang der am geteilten Telefon angemeldeten Person (Rosenweg Gerät). */
    private fun vomGeraet(): Konto? = try {
        ctx.contentResolver.call(
            android.net.Uri.parse("content://ch.rosenweg4303.telefon.anlage"), "nextcloud_konto", ctx.packageName, null
        )?.let { b ->
            val server = b.getString("server")?.trimEnd('/')
            val benutzer = b.getString("benutzer")
            val pw = b.getString("app_passwort")
            if (server == ROSENWEG_NEXTCLOUD && !benutzer.isNullOrEmpty() && !pw.isNullOrEmpty())
                Konto(server, benutzer, pw, vomGeraet = true) else null
        }
    } catch (e: Exception) {   // keine Geraete-App oder keine Berechtigung (privates Handy)
        null
    }

    private fun eigenes(): Konto? {
        val server = prefs.getString(SERVER, null) ?: return null
        val benutzer = prefs.getString(BENUTZER, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val geheim = prefs.getString(PASSWORT, null) ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            Konto(server, benutzer, String(c.doFinal(Base64.decode(geheim, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (e: Exception) {
            // Schluessel weg (z.B. Sperre geaendert) -> neu anmelden
            loeschen()
            null
        }
    }

    fun speichern(k: Konto) {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, schluessel())
        prefs.edit()
            .putString(SERVER, k.server)
            .putString(BENUTZER, k.benutzer)
            .putString(IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString(PASSWORT, Base64.encodeToString(c.doFinal(k.appPasswort.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .apply()
    }

    fun loeschen() {
        prefs.edit().clear().apply()
    }

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "rosenweg_cloud"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SERVER = "server"
        const val BENUTZER = "benutzer"
        const val IV = "iv"
        const val PASSWORT = "app_passwort"
    }
}
