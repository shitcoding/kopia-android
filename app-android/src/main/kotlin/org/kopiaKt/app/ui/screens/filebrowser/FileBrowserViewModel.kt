package org.kopiaKt.app.ui.screens.filebrowser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.usecase.BrowseSnapshotUseCase
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val browseSnapshotUseCase: BrowseSnapshotUseCase
) : ViewModel() {

    private val snapshotId: String = savedStateHandle["snapshotId"] ?: ""

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val entries = browseSnapshotUseCase(snapshotId, path)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentPath = path,
                        entries = entries,
                        pathHistory = buildPathHistory(path)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load directory"
                    )
                }
            }
        }
    }

    private fun buildPathHistory(path: String): List<PathSegment> {
        if (path.isEmpty()) {
            return listOf(PathSegment("/", ""))
        }

        val segments = mutableListOf(PathSegment("/", ""))
        var currentPath = ""

        for (part in path.trim('/').split('/')) {
            if (part.isEmpty()) continue
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            segments.add(PathSegment(part, currentPath))
        }

        return segments
    }
}

data class FileBrowserUiState(
    val isLoading: Boolean = false,
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val pathHistory: List<PathSegment> = listOf(PathSegment("/", "")),
    val error: String? = null
)

data class PathSegment(
    val name: String,
    val fullPath: String
)
