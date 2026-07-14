package com.pricefighter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pricefighter.data.model.PriceReport
import com.pricefighter.data.repo.PriceCheckRepository
import com.pricefighter.data.vision.ProductCandidate
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

    /**
     * Identified and priced; the report is saved to history. [alternatives] are the other
     * candidates the detector considered, so the user can re-run against a different match when
     * the top guess was wrong.
     */
    data class Success(
        val report: PriceReport,
        val via: String,
        val alternatives: List<ProductCandidate> = emptyList(),
    ) : CaptureState

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

    /** Every candidate the last snap produced, so the user can switch to a different match. */
    private var candidates: List<ProductCandidate> = emptyList()

    /** Runs the on-device identification then prices the best candidate. */
    fun onPhotoCaptured(file: File) {
        val lookupId = ++activeLookupId
        _state.value = CaptureState.Working("Identifying…")
        singleJob = viewModelScope.launch {
            val found = runCatching { identifier.identify(file) }.getOrDefault(emptyList())
            if (found.isEmpty()) {
                if (activeLookupId == lookupId) {
                    _state.value = CaptureState.NeedsGemini("Couldn’t identify it on-device — opening Gemini…")
                }
                return@launch
            }
            candidates = found
            priceCandidate(found.first(), lookupId)
        }
    }

    /** Re-run the price check against a different candidate the user picked from the result card. */
    fun selectCandidate(candidate: ProductCandidate) {
        val lookupId = ++activeLookupId
        singleJob = viewModelScope.launch { priceCandidate(candidate, lookupId) }
    }

    private suspend fun priceCandidate(candidate: ProductCandidate, lookupId: Long) {
        if (activeLookupId == lookupId) {
            _state.value = CaptureState.Working("Pricing “${candidate.searchTerm}”…")
        }
        val report = runCatching { repository.priceCheck(candidate.searchTerm, "") }
            .getOrElse {
                if (activeLookupId == lookupId) {
                    _state.value = CaptureState.Error("Price check failed: ${it.message}")
                }
                return
            }
        if (activeLookupId == lookupId) {
            _state.value = CaptureState.Success(
                report = report,
                via = candidate.via,
                alternatives = candidates.filterNot { it.searchTerm == candidate.searchTerm },
            )
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
            // Continuous mode has no picker, so it just takes the best candidate and keeps moving.
            val best = runCatching { identifier.identify(file) }.getOrDefault(emptyList()).firstOrNull()
            if (best == null) {
                // No app-switch in continuous mode — just flag it so the flow isn't interrupted.
                updateItem(id, ItemStatus.Unidentified)
                return@launch
            }
            val report = runCatching { repository.priceCheck(best.searchTerm, "") }.getOrNull()
            updateItem(id, if (report != null) ItemStatus.Done(report, best.via) else ItemStatus.Failed)
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
