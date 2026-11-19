package com.poppinzhang.natsuko

import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.poppinzhang.natsuko.data.AppearancePreferences
import com.poppinzhang.natsuko.data.ShareImportParser
import com.poppinzhang.natsuko.data.applyApplicationTheme
import com.poppinzhang.natsuko.model.SplitOrientationHint
import com.poppinzhang.natsuko.processing.ImageSplitGeometry
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: NatsukoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyApplicationTheme(this, AppearancePreferences(this).read())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::applySplitFullscreenWindow)
            }
        }
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
        // Neither a recreated activity nor a task reopened from Recents imports its share again.
        if (savedInstanceState == null && ShareImportParser.isNewShare(intent)) {
            viewModel.handleShareIntent(intent)
        }
    }

    private fun applySplitFullscreenWindow(state: NatsukoUiState) {
        val splitFullscreen = state.screen == AppScreen.SPLIT_FULLSCREEN
        val desiredOrientation = if (splitFullscreen) {
            when (state.split?.source?.let { ImageSplitGeometry.fullscreenOrientation(it.width, it.height) }) {
                SplitOrientationHint.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                SplitOrientationHint.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != desiredOrientation) {
            requestedOrientation = desiredOrientation
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (splitFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleShareIntent(intent)
    }
}
