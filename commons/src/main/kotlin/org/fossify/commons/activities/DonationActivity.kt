package org.fossify.commons.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.fossify.commons.R
import org.fossify.commons.compose.extensions.enableEdgeToEdgeSimple
import org.fossify.commons.compose.screens.DonationScreen
import org.fossify.commons.compose.theme.AppThemeSurface
import org.fossify.commons.extensions.openWebsiteIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.Donation

class DonationActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rosenweg: Seite gibt es nicht mehr.
        finish()
        return
        @Suppress("UNREACHABLE_CODE")
        enableEdgeToEdgeSimple()
        setContent {
            val clipboardManager = LocalClipboardManager.current
            AppThemeSurface {
                DonationScreen(
                    donationOptions = FossifyDonationPlatforms,
                    cryptoAddresses = FossifyCryptoAddresses,
                    goBack = ::finish,
                    openWebsite = ::openWebsiteIntent,
                    copyToClipboard = {
                        clipboardManager.setText(AnnotatedString(it))
                        toast(R.string.value_copied_to_clipboard)
                    },
                )
            }
        }
    }
}

// Rosenweg: keine Spendenwege (Fossify-Konten, Krypto-Adressen) in den Forks.
// Die Rosenweg-Apps laufen auf verwalteten Telefonen ohne Browser; Fossify
// selbst laesst sich direkt unter fossify.org unterstuetzen.
internal val FossifyDonationPlatforms = emptyList<Donation.Platform>()

internal val FossifyCryptoAddresses = emptyList<Donation.Crypto>()
