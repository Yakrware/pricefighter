package com.pricefighter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pricefighter.data.model.PriceReport
import com.pricefighter.data.repo.PriceCheckRepository
import com.pricefighter.data.vision.ProductIdentifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** State of a single-shot camera price check. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /** Identifying the item, then pricing it. [step] is shown next to the spinner. */
    data class Working(val step: String) : CaptureState

    /** Identified and priced; the report is saved to history. */
    data class Success(val report: PriceReport, val via: String) : CaptureState

    /** Couldn't identify on-device — the screen hands the photo to the Gemini app (tier 4). */
    data class NeedsGemini(val message: String) : CaptureState

    data class Error(val message: String) : CaptureState
}

class CameraViewModel(
    private val repository: PriceCheckRepository,
    private val identifier: ProductIdentifier,
) : ViewModel() {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** Runs the tier 1–3 identification then a price check on the captured photo. */
    fun onPhotoCaptured(file: File) {
        _state.value = CaptureState.Working("Identifying…")
        viewModelScope.launch {
            val identification = runCatching { identifier.identify(file) }.getOrNull()
            if (identification == null) {
                _state.value = CaptureState.NeedsGemini("Couldn’t identify it on-device — opening Gemini…")
                return@launch
            }

            _state.value = CaptureState.Working("Pricing “${identification.searchTerm}”…")
            val report = runCatching { repository.priceCheck(identification.searchTerm, "") }
                .getOrElse {
                    _state.value = CaptureState.Error("Price check failed: ${it.message}")
                    return@launch
                }
            _state.value = CaptureState.Success(report, identification.via)
        }
    }

    fun reset() {
        _state.value = CaptureState.Idle
    }

    companion object {
        fun factory(repository: PriceCheckRepository, identifier: ProductIdentifier) = viewModelFactory {
            initializer { CameraViewModel(repository, identifier) }
        }
    }
}
