package org.kopiaKt.app.ui.screens.snapshotlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.usecase.ListSnapshotsUseCase
import javax.inject.Inject

@HiltViewModel
class SnapshotListViewModel @Inject constructor(
    private val listSnapshotsUseCase: ListSnapshotsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SnapshotListUiState())
    val uiState: StateFlow<SnapshotListUiState> = _uiState.asStateFlow()

    init {
        loadSnapshots()
    }

    fun loadSnapshots(source: SourceInfo? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val snapshots = listSnapshotsUseCase(source)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        snapshots = snapshots,
                        selectedSource = source
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load snapshots"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadSnapshots(_uiState.value.selectedSource)
    }
}

data class SnapshotListUiState(
    val isLoading: Boolean = false,
    val snapshots: List<SnapshotInfo> = emptyList(),
    val selectedSource: SourceInfo? = null,
    val error: String? = null
)
