package app.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import app.exceptions.MissingDiffEntryException
import app.extensions.filePath
import app.git.*
import app.git.diff.DiffResult
import app.preferences.AppSettings
import app.git.diff.GenerateSplitHunkFromDiffResultUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.revwalk.RevCommit
import javax.inject.Inject

class HistoryViewModel @Inject constructor(
    private val tabState: TabState,
    private val diffManager: DiffManager,
    private val settings: AppSettings,
    private val generateSplitHunkFromDiffResultUseCase: GenerateSplitHunkFromDiffResultUseCase,
) {
    private val _historyState = MutableStateFlow<HistoryState>(HistoryState.Loading(""))
    val historyState: StateFlow<HistoryState> = _historyState

    private val _viewDiffResult = MutableStateFlow<ViewDiffResult>(ViewDiffResult.None)
    val viewDiffResult: StateFlow<ViewDiffResult> = _viewDiffResult
    var filePath: String = ""

    val lazyListState = MutableStateFlow(
        LazyListState(
            0,
            0
        )
    )


    init {
        tabState.managerScope.launch {
            settings.textDiffTypeFlow.collect { diffType ->
                if (filePath.isNotBlank()) {
                    updateDiffType(diffType)
                }
            }
        }
    }

    private fun updateDiffType(newDiffType: TextDiffType) {
        val viewDiffResult = this.viewDiffResult.value

        if (viewDiffResult is ViewDiffResult.Loaded) {
            val diffResult = viewDiffResult.diffResult

            if (diffResult is DiffResult.Text && newDiffType == TextDiffType.SPLIT) { // Current is unified and new is split
                val hunksList = generateSplitHunkFromDiffResultUseCase(diffResult)
                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffEntryType = viewDiffResult.diffEntryType,
                    diffResult = DiffResult.TextSplit(diffResult.diffEntry, hunksList)
                )
            } else if (diffResult is DiffResult.TextSplit && newDiffType == TextDiffType.UNIFIED) { // Current is split and new is unified
                val hunksList = diffResult.hunks.map { it.sourceHunk }

                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffEntryType = viewDiffResult.diffEntryType,
                    diffResult = DiffResult.Text(diffResult.diffEntry, hunksList)
                )
            }
        }
    }

    fun fileHistory(filePath: String) = tabState.safeProcessing(
        refreshType = RefreshType.NONE,
    ) { git ->
        this.filePath = filePath
        _historyState.value = HistoryState.Loading(filePath)

        val log = git.log()
            .addPath(filePath)
            .call()
            .toList()

        _historyState.value = HistoryState.Loaded(filePath, log)
    }

    fun selectCommit(commit: RevCommit) = tabState.runOperation(
        refreshType = RefreshType.NONE,
        showError = true,
    ) { git ->

        try {
            val diffEntries = diffManager.commitDiffEntries(git, commit)
            val diffEntry = diffEntries.firstOrNull { entry ->
                entry.filePath == this.filePath
            }

            if (diffEntry == null) {
                _viewDiffResult.value = ViewDiffResult.DiffNotFound
                return@runOperation
            }

            val diffEntryType = DiffEntryType.CommitDiff(diffEntry)

            val diffResult = diffManager.diffFormat(git, diffEntryType)
            val textDiffType = settings.textDiffType

            val formattedDiffResult = if (textDiffType == TextDiffType.SPLIT && diffResult is DiffResult.Text) {
                DiffResult.TextSplit(diffEntry, generateSplitHunkFromDiffResultUseCase(diffResult))
            } else
                diffResult

            _viewDiffResult.value = ViewDiffResult.Loaded(diffEntryType, formattedDiffResult)
        } catch (ex: Exception) {
            if (ex is MissingDiffEntryException) {
                tabState.refreshData(refreshType = RefreshType.UNCOMMITED_CHANGES)
                _viewDiffResult.value = ViewDiffResult.DiffNotFound
            } else
                ex.printStackTrace()
        }
    }
}

sealed class HistoryState(val filePath: String) {
    class Loading(filePath: String) : HistoryState(filePath)
    class Loaded(filePath: String, val commits: List<RevCommit>) : HistoryState(filePath)
}

