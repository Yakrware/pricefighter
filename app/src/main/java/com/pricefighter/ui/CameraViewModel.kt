package com.pricefighter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pricefighter.data.model.PriceReport
import com.pricefighter.data.repo.PriceCheckRepository
import com.pricefighter.data.vision.ProductIdentifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** One capture within a continuous-mode session. */
data class CaptureItem(val id: Long, val status: ItemStatus)

sealed interface ItemStatus {
    data object Working : ItemStatus
    data class Done(val report: PriceReport, val via: String) : ItemStatus
    data object Unidentified : ItemStatus
    data object Failed : ItemStatus
}

class CameraViewModel(
    private val repository: PriceCheckRepository,
    private val identifier: ProductIdentifier,
) : ViewModel() {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var singleJob: Job? = null

    // Bumped whenever the visible lookup is dismissed (cancelled or sent to the background),
    // so a still-running coroutine stops updating the on-screen state but keeps saving to
    // History. Only the coroutine whose id still matches may touch [_state].
    private var activeLookupId = 0L

    /** Runs the tier 1–3 identification then a price check on the captured photo. */
    fun onPhotoCaptured(file: File) {
        val lookupId = ++activeLookupId
        _state.value = CaptureState.Working("Identifying…")
        singleJob = viewModelScope.launch {
            val identification = runCatching { identifier.identify(file) }.getOrNull()
            if (identification == null) {
                if (activeLookupId == lookupId) {
                    _state.value = CaptureState.NeedsGemini("Couldn’t identify it on-device — opening Gemini…")
                }
                return@launch
            }

            if (activeLookupId == lookupId) {
                _state.value = CaptureState.Working("Pricing “${identification.searchTerm}”…")
            }
            val report = runCatching { repository.priceCheck(identification.searchTerm, "") }
                .getOrElse {
                    if (activeLookupId == lookupId) {
                        _state.value = CaptureState.Error("Price check failed: ${it.message}")
                    }
                    return@launch
                }
            if (activeLookupId == lookupId) {
                _state.value = CaptureState.Success(report, identification.via)
            }
        }
    }

    /**
     * Return to the live camera. A still-running lookup keeps going in the background and
     * still saves its report to History — it just stops driving the visible state.
     */
    fun reset() {
        activeLookupId++
        _state.value = CaptureState.Idle
    }

    /** Abort the in-flight lookup entirely and return to the live camera. */
    fun cancelSingle() {
        singleJob?.cancel()
        reset()
    }

    // ---- Continuous mode: each snap runs its lookup in the background; results accumulate. ----

    private val _items = MutableStateFlow<List<CaptureItem>>(emptyList())
    val items: StateFlow<List<CaptureItem>> = _items.asStateFlow()
    private var nextItemId = 0L

    /** Snap-and-keep-going: identify + price in the background without blocking the camera. */
    fun captureContinuous(file: File) {
        val id = nextItemId++
        _items.update { it + CaptureItem(id, ItemStatus.Working) }
        viewModelScope.launch {
            val identification = runCatching { identifier.identify(file) }.getOrNull()
            if (identification == null) {
                // No app-switch in continuous mode — just flag it so the flow isn't interrupted.
                updateItem(id, ItemStatus.Unidentified)
                return@launch
            }
            val report = runCatching { repository.priceCheck(identification.searchTerm, "") }.getOrNull()
            updateItem(
                id,
                if (report != null) ItemStatus.Done(report, identification.via) else ItemStatus.Failed,
            )
        }
    }

    fun clearSession() {
        _items.value = emptyList()
    }

    private fun updateItem(id: Long, status: ItemStatus) {
        _items.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
    }

    companion object {
        fun factory(repository: PriceCheckRepository, identifier: ProductIdentifier) = viewModelFactory {
            initializer { CameraViewModel(repository, identifier) }
        }
    }
}
