package ch.widmer.airview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.widmer.airview.ui.AirTheme
import ch.widmer.airview.ui.LocalPalette
import ch.widmer.airview.ui.Type

/** Von Health Connect verlangt: erklärt, wofür die Daten genutzt werden. */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirTheme {
                val p = LocalPalette.current
                Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().padding(28.dp)) {
                    Text("Datenschutz", style = Type.numeral.copy(fontSize = 44.sp), color = p.ink)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Wolof Health liest deine Gesundheitsdaten aus Health Connect nur, um sie auf diesem Gerät anzuzeigen. " +
                            "Es werden keine Gesundheitsdaten gespeichert, geschrieben oder übertragen. Ins Internet geht die App nur, um auf GitHub nach einer neuen Version von sich selbst zu suchen.",
                        style = Type.body, color = p.muted,
                    )
                }
            }
        }
    }
}
