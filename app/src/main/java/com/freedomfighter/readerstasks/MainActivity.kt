package com.freedomfighter.readerstasks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readerstasks.ui.AccountScreen
import com.freedomfighter.readerstasks.ui.ListsScreen
import com.freedomfighter.readerstasks.ui.LocalColors
import com.freedomfighter.readerstasks.ui.Nav
import com.freedomfighter.readerstasks.ui.ReaderTheme
import com.freedomfighter.readerstasks.ui.Screen
import com.freedomfighter.readerstasks.ui.SettingsScreen
import com.freedomfighter.readerstasks.ui.TasksScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        setContent {
            val settings by app.prefs.settings.collectAsState()
            val nav = remember { Nav() }
            ReaderTheme(settings) {
                Bars()
                when (nav.current) {
                    Screen.Tasks -> TasksScreen(nav, app)
                    Screen.Lists -> ListsScreen(nav, app)
                    Screen.Settings -> SettingsScreen(nav, app)
                    Screen.Account -> AccountScreen(nav, app)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as App
        if (app.prefs.settings.value.hasAccount && app.store.isStale(2 * 60_000L)) app.store.syncAll()
    }
}

@Composable
private fun Bars() {
    val view = LocalView.current
    val colors = LocalColors.current
    LaunchedEffect(colors.isDark) {
        val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, view).apply { isAppearanceLightStatusBars = !colors.isDark; isAppearanceLightNavigationBars = !colors.isDark }
    }
}
