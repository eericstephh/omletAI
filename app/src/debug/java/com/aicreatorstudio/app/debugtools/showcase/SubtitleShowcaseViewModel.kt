package com.aicreatorstudio.app.debugtools.showcase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.data.subtitle.JsonTemplateCatalog
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.template.JsonCatalogStatus
import com.aicreatorstudio.app.model.template.JsonTemplateMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PHASE 5 — load state of the showcase's template registry.
 *
 * [catalogStatus] is carried through unchanged from the production mapper so
 * a [JsonCatalogStatus.EMPTY] fallback is visible on screen instead of
 * silently looking like a small catalog.
 */
data class ShowcaseRegistryState(
    val loading: Boolean = true,
    val templates: List<SubtitleTemplate> = emptyList(),
    val catalogStatus: JsonCatalogStatus? = null,
    val error: String? = null
)

/**
 * PHASE 5 — loads the showcase registry through the PRODUCTION pipeline.
 *
 * [JsonTemplateCatalog] is the same Hilt singleton the editor uses (asset
 * reader + cache), `JsonTemplateMapper.mapCatalogState` is the same mapper,
 * and `BuiltInSubtitleTemplates.ALL` is the same hand-built catalogue.
 * Nothing here re-implements template parsing or keeps a parallel id list,
 * so the showcase cannot drift from what the picker offers.
 */
@HiltViewModel
class SubtitleShowcaseViewModel @Inject constructor(
    private val jsonCatalog: JsonTemplateCatalog
) : ViewModel() {

    private val _registry = MutableStateFlow(ShowcaseRegistryState())
    val registry: StateFlow<ShowcaseRegistryState> = _registry.asStateFlow()

    init {
        viewModelScope.launch {
            _registry.value = runCatching {
                val mapped = JsonTemplateMapper.mapCatalogState(jsonCatalog.load())
                ShowcaseRegistryState(
                    loading = false,
                    templates = ShowcaseFixture.combinedRegistry(mapped.templates),
                    catalogStatus = mapped.status
                )
            }.getOrElse { error ->
                ShowcaseRegistryState(
                    loading = false,
                    error = error.message ?: error.toString()
                )
            }
        }
    }
}
