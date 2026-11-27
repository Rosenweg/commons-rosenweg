package ch.rosenweg4303.updater

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Bild der Update-Seite in hell und dunkel: RW_BILDER=<ordner> ./gradlew :updater:testDebugUnitTest */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateSeiteBildTest {
    /** Logos aus RW_LOGOS (z.B. Website img/apps), sonst rote Flaeche. */
    private fun symbol(farbe: Int, name: String = ""): android.graphics.drawable.Drawable {
        val f = System.getenv("RW_LOGOS")?.let { File(it, "$name.png") }
        if (f != null && f.exists()) {
            val b = android.graphics.BitmapFactory.decodeFile(f.path)
            if (b != null) return android.graphics.drawable.BitmapDrawable(
                ApplicationProvider.getApplicationContext<Context>().resources, b)
        }
        return GradientDrawable().apply { setColor(farbe); cornerRadius = 40f }
    }

    private fun seite(dunkel: Boolean): Bitmap {
        val basis: Context = ApplicationProvider.getApplicationContext()
        val cfg = Configuration(basis.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dunkel) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val c = basis.createConfigurationContext(cfg)
        val logo = symbol(0xFFD62828.toInt(), "rosenweg-notizen")
        val a = UpdateAnsicht(c, dunkel, gruppeFest = 0xFFD62828.toInt(), logo = logo)
        a.kopf("3 neue Fassungen", "für Rosenweg-Apps auf diesem Handy")
        val z1 = a.Zeile("ch.rosenweg4303.phone", "Rosenweg Telefon", "1.11.5", "1.11.6", symbol(0xFFD62828.toInt(), "rosenweg-telefon"))
        val z2 = a.Zeile("ch.rosenweg4303.contacts", "Rosenweg Kontakte", "1.7.5", "1.7.6", symbol(0xFFD62828.toInt(), "rosenweg-kontakte"))
        val z3 = a.Zeile("ch.rosenweg4303.salz", "Rosenweg Salz", "1.0.4", "1.0.5", symbol(0xFFD62828.toInt(), "rosenweg-salz"))
        val z4 = a.Zeile("ch.rosenweg4303.vm", "Rosenweg Virtuelle Computer", "1.2.4", "1.3.1", symbol(0xFFD62828.toInt(), "rosenweg-vm"))
        val z5 = a.Zeile("ch.rosenweg4303.clock", "Rosenweg Uhr", "1.7.6", "1.7.7", symbol(0xFFD62828.toInt(), "rosenweg-uhr"))
        a.zeilenSetzen(listOf(z1, z2, z3, z4, z5))
        z1.setzen(UpdateAnsicht.Zustand.INSTALLIERT)
        z2.setzen(UpdateAnsicht.Zustand.LAEDT, anteil = 0.62f)
        z3.setzen(UpdateAnsicht.Zustand.FEHLER, "Google Play Protect hat die Installation verhindert")
        z4.setzen(UpdateAnsicht.Zustand.BESTAETIGEN)
        a.hinweis("Damit Rosenweg-Apps Updates installieren dürfen, einmal „Apps aus dieser Quelle zulassen“ " +
            "einschalten und dann zurück tippen – es geht danach von selbst weiter.", "Erlauben")
        a.hauptknopf("Alle installieren")
        val w = 1080; val h = 2340
        val v = a.wurzel
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    @Test
    fun bild() {
        val ordner = System.getenv("RW_BILDER")
        assumeTrue(!ordner.isNullOrBlank())
        val hell = seite(false); val dunkel = seite(true)
        val gesamt = Bitmap.createBitmap(hell.width * 2 + 60, hell.height, Bitmap.Config.ARGB_8888)
        Canvas(gesamt).apply {
            drawColor(Color.rgb(120, 120, 120))
            drawBitmap(hell, 0f, 0f, null); drawBitmap(dunkel, hell.width + 60f, 0f, null)
        }
        File(ordner!!).mkdirs()
        File(ordner, "updater-neu.png").outputStream().use { gesamt.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
