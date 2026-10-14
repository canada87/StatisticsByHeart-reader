package app.sbh.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.ui.AppRoot
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.theme.SbhTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val reduce by main.reduceMotion.collectAsStateWithLifecycle(initialValue = false)
            SbhTheme(reduceMotionSetting = reduce) {
                AppRoot(main)
            }
        }
    }
}
