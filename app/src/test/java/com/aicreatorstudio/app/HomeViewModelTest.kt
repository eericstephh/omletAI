package com.aicreatorstudio.app

import com.aicreatorstudio.app.data.AppChangelogProvider
import com.aicreatorstudio.app.data.InMemoryAppUpdatePreferences
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.video.InMemoryProjectRepository
import com.aicreatorstudio.app.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts with no projects`() = runTest(testDispatcher) {
        val viewModel = HomeViewModel(InMemoryProjectRepository(), InMemoryAppUpdatePreferences())
        assertTrue(viewModel.uiState.value.projects.isEmpty())
    }

    @Test
    fun `shows a project created in the repository`() = runTest(testDispatcher) {
        val repository = InMemoryProjectRepository()
        val viewModel = HomeViewModel(repository, InMemoryAppUpdatePreferences())

        // uiState is built with stateIn(WhileSubscribed), so it only starts
        // collecting from the repository once something subscribes to it.
        val collectorJob = launch { viewModel.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        repository.createProjectFromClip(sampleClip(), name = "Test Project")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.projects.size)
        assertEquals("Test Project", viewModel.uiState.value.projects.first().name)

        collectorJob.cancel()
    }

    // ---- The changelog bell ----

    @Test
    fun `a fresh install shows the unread badge and acknowledging clears it`() =
        runTest(testDispatcher) {
            // End-to-end wiring for the bell: repository + preferences ->
            // uiState. The decision itself is covered by `AppUpdateInfoTest`;
            // this covers what that test cannot reach — that `HomeViewModel`
            // really folds the seen marker into `hasUnreadUpdates` and
            // publishes the changelog. `InMemoryAppUpdatePreferences()` starts
            // at marker 0, which is exactly a fresh install.
            val preferences = InMemoryAppUpdatePreferences()
            val viewModel = HomeViewModel(InMemoryProjectRepository(), preferences)

            // stateIn(WhileSubscribed): nothing is computed until something
            // subscribes.
            val collectorJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasUnreadUpdates)
            assertTrue(viewModel.uiState.value.releases.isNotEmpty())
            assertEquals(
                AppChangelogProvider.currentVersionCode,
                viewModel.uiState.value.currentVersionCode
            )

            viewModel.markUpdatesAsRead()
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasUnreadUpdates)

            collectorJob.cancel()
        }

    private fun sampleClip() = VideoClip(
        id = "clip-1",
        uri = "content://sample",
        fileName = "sample.mp4",
        durationMs = 5_000L,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1_000_000L,
        importedAtEpochMillis = 0L
    )
}
