package io.github.kopolot.hcsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect as this app's privacy policy / permission usage explanation. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Jak HC Sheets Sync używa Twoich danych", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Aplikacja odczytuje z Health Connect: posiłki (kalorie i makro), spalone kalorie, " +
                                "treningi, wagę, tkankę tłuszczową, kroki, tętno spoczynkowe i sen."
                        )
                        Text(
                            "Raz dziennie liczy z nich podsumowanie per dzień i wysyła je wyłącznie do Twojego " +
                                "arkusza Google (przez Twój własny Apps Script). Nic innego nie jest nigdzie wysyłane, " +
                                "aplikacja niczego nie zapisuje do Health Connect."
                        )
                        Text(
                            "Odczyt w tle jest potrzebny do automatycznego codziennego syncu, a odczyt historii – " +
                                "do jednorazowego uzupełnienia danych sprzed ponad 30 dni."
                        )
                        Text("Zgody możesz w każdej chwili cofnąć w ustawieniach Health Connect.")
                        Button(onClick = { finish() }) { Text("OK") }
                    }
                }
            }
        }
    }
}
