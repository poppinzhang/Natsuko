package com.poppinzhang.natsuko

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poppinzhang.natsuko.data.AppearancePreferences
import com.poppinzhang.natsuko.data.applyApplicationTheme
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NatsukoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyApplicationTheme(this, AppearancePreferences(this).read())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.usesDarkTheme(isSystemInDarkTheme())
            LaunchedEffect(themeMode) { applyApplicationTheme(this@MainActivity, themeMode) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            NatsukoTheme(themeMode = themeMode) {
                NatsukoApp(viewModel)
            }
        }
        if (savedInstanceState == null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            viewModel.handleShareIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleShareIntent(intent)
    }
}
