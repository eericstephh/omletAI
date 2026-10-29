package com.aicreatorstudio.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.data.AppChangelogProvider
import com.aicreatorstudio.app.data.AppUpdatePreferences
import com.aicreatorstudio.app.model.HomeUiState
import com.aicreatorstudio.app.model.hasUnreadUpdates
import com.aicreatorstudio.app.video.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for [com.aicreatorstudio.app.ui.screens.home.HomeScreen].
 *
 * Projects come straight from [ProjectRepository], so any project created
 * elsewhere (e.g. saved from the import flow) shows up here automatically —
 * the Composable never talks to the repository directly, keeping the MVVM
 * boundary intact.
 *
 * ## The changelog bell
 *
 * Two independent sources feed one state object:
 *
 *  - [ProjectRepository.projects] — changes when the user creates, renames or
 *    deletes a project.
 *  - [AppUpdatePreferences.lastSeenVersionCode] — changes when the user taps
 *    the bell and acknowledges the changelog.
 *
 * They are `combine`d rather than merged into one flow so that acknowledging
 * the changelog recomputes the badge without re-emitting the project list,
 * and vice versa. The unread decision itself is
 * [com.aicreatorstudio.app.model.hasUnreadUpdates] — a pure function, so the
 * part of this feature that can actually be wrong is unit-tested even though
 * this ViewModel (Hilt, constructor-injected, no Robolectric in the project)
 * cannot be.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val appUpdatePreferences: AppUpdatePreferences
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        projectRepository.projects,
        appUpdatePreferences.lastSeenVersionCode
    ) { projects, lastSeenVersionCode ->
        HomeUiState(
            projects = projects,
            releases = AppChangelogProvider.releases,
            currentVersionCode = AppChangelogProvider.currentVersionCode,
            currentVersionName = AppChangelogProvider.currentVersionName,
            hasUnreadUpdates = hasUnreadUpdates(
                currentVersionCode = AppChangelogProvider.currentVersionCode,
                lastSeenVersionCode = lastSeenVersionCode
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    /**
     * Record that the user has seen the current release, clearing the bell's
     * red dot.
     *
     * Called when the changelog sheet is opened *and* from its "متوجه شدم"
     * button. Both, deliberately: the badge is specified to disappear "once
     * the user views the latest update", which is the moment the sheet opens,
     * while the button is the explicit acknowledgement. The write is
     * idempotent and the stored marker only ever moves forward, so calling it
     * twice costs one comparison.
     */
    fun markUpdatesAsRead() {
        appUpdatePreferences.markUpdatesAsRead(AppChangelogProvider.currentVersionCode)
    }

    fun deleteProject(projectId: String) {
        projectRepository.deleteProject(projectId)
    }
}
