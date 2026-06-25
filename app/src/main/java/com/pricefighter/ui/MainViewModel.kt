package com.pricefighter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pricefighter.data.db.HistoryEntity
import com.pricefighter.data.repo.PriceCheckRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: PriceCheckRepository) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> = repository.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun deleteEntry(id: Long) = viewModelScope.launch { repository.deleteEntry(id) }

    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }

    companion object {
        fun factory(repository: PriceCheckRepository) = viewModelFactory {
            initializer { MainViewModel(repository) }
        }
    }
}
