package com.poppinzhang.natsuko.ui

import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.processing.location.LocationConflict
import com.poppinzhang.natsuko.processing.location.LocationSource

data class LocationCandidate(val input: InputItem, val source: LocationSource? = null, val error: UiText? = null)

data class CopyLocationUiState(
    val source: LocationSource? = null,
    val candidates: List<LocationCandidate> = emptyList(),
    val isLoading: Boolean = false,
    val conflict: LocationConflict? = null,
    val targetsPickerRequested: Boolean = false,
    val sourcePickerRequested: Boolean = false,
)
