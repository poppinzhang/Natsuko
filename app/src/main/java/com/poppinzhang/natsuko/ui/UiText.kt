package com.poppinzhang.natsuko.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.poppinzhang.natsuko.model.UiText

@Composable
fun UiText.asString(): String {
    // Re-read resources even when a retained ViewModel emits the same resource-backed value.
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
