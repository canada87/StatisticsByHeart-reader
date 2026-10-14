package app.sbh.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.PushedHeader
import app.sbh.reader.ui.components.SectionLabel
import app.sbh.reader.ui.theme.SbhTheme

/**
 * Health Connect permission rationale (`ACTION_SHOW_PERMISSIONS_RATIONALE`, also reachable as
 * `VIEW_PERMISSION_USAGE` from the HC settings): what is read, where it goes, what is never done.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SbhTheme {
                RationaleScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun RationaleScreen(onBack: () -> Unit) {
    val c = SbhTheme.colors
    val main = HealthTypes.all.filter { it.tier == 1 }.map { stringResource(it.labelRes) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PushedHeader(stringResource(R.string.rationale_title), onBack)
        HudPanel {
            SectionLabel(stringResource(R.string.rationale_what_title))
            Text(stringResource(R.string.rationale_what_body), style = MaterialTheme.typography.bodyMedium, color = c.fg)
            Text(main.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
        HudPanel {
            SectionLabel(stringResource(R.string.rationale_where_title))
            Text(stringResource(R.string.rationale_where_body), style = MaterialTheme.typography.bodyMedium, color = c.fg)
        }
        HudPanel {
            SectionLabel(stringResource(R.string.rationale_never_title))
            Text(stringResource(R.string.rationale_never_body), style = MaterialTheme.typography.bodyMedium, color = c.fg)
        }
    }
}
