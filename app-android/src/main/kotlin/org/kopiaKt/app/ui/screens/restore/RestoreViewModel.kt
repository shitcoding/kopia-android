package org.kopiaKt.app.ui.screens.restore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.usecase.RestoreFilesUseCase
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val restoreFilesUseCase: RestoreFilesUseCase
) : ViewModel() {

    private val snapshotId: String = savedStateHandle["snapshotId"] ?: ""
    private val sourcePath: String = savedStateHandle["sourcePath"] ?: ""

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    fun startRestore(destinationUri: String) {
        viewModelScope.launch {
            restoreFilesUseCase(snapshotId, sourcePath, destinationUri)
                .collect { progress ->
                    _uiState.update {
                        it.copy(progress = progress)
                    }
                }
        }
    }

    fun cancelRestore() {
        restoreFilesUseCase.cancel()
    }
}

data class RestoreUiState(
    val progress: RestoreProgress = RestoreProgress(
        state = RestoreState.IDLE,
        totalFiles = 0,
        restoredFiles = 0,
        totalBytes = 0,
        restoredBytes = 0,
        currentFile = null,
        errorMessage = null
    )
)
