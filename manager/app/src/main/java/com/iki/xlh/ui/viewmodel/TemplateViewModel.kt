package com.iki.xlh.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import com.iki.xlh.Natives
import com.iki.xlh.data.repository.TemplateRepository
import com.iki.xlh.data.repository.TemplateRepositoryImpl
import com.iki.xlh.profile.Capabilities
import com.iki.xlh.profile.Groups
import com.iki.xlh.ui.screen.template.TemplateUiState
import com.iki.xlh.ui.util.getAppProfileTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

const val TAG = "TemplateViewModel"

class TemplateViewModel(
    private val repo: TemplateRepository = TemplateRepositoryImpl()
) : ViewModel() {

    typealias TemplateInfo = com.iki.xlh.data.model.TemplateInfo

    private val _uiState = MutableStateFlow(TemplateUiState())
    val uiState: StateFlow<TemplateUiState> = _uiState.asStateFlow()

    suspend fun fetchTemplates(sync: Boolean = false) {
        _uiState.update { it.copy(isRefreshing = true, error = null) }

        val result = repo.getTemplates(sync)

        withContext(Dispatchers.Main) {
            result.onSuccess { templates ->
                val comparator = compareBy(TemplateInfo::local).reversed().then(
                    compareBy(
                        Collator.getInstance(Locale.getDefault()), TemplateInfo::id
                    )
                )
                val sorted = templates.sortedWith(comparator)

                _uiState.update {
                    it.copy(
                        templates = templates,
                        templateList = sorted,
                        isRefreshing = false
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e
                    )
                }
            }
        }
    }

    suspend fun importTemplates(
        templates: String,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (String) -> Unit
    ) {
        repo.importTemplates(templates)
            .onSuccess { onSuccess() }
            .onFailure { onFailure(it.message ?: "Unknown error") }
    }

    suspend fun exportTemplates(onTemplateEmpty: suspend () -> Unit, callback: suspend (String) -> Unit) {
        repo.exportTemplates()
            .onSuccess { callback(it) }
            .onFailure { onTemplateEmpty() }
    }
}

fun getTemplateInfoById(id: String): com.iki.xlh.data.model.TemplateInfo? {
    return runCatching {
        com.iki.xlh.data.model.TemplateInfo.fromJSON(JSONObject(getAppProfileTemplate(id)))
    }.onFailure {
        Log.e(TAG, "ignore invalid template: $it", it)
    }.getOrNull()
}

@Suppress("unused")
fun generateTemplates() {
    val templateJson = JSONObject()
    templateJson.put("id", "com.example")
    templateJson.put("name", "Example")
    templateJson.put("description", "This is an example template")
    templateJson.put("local", true)
    templateJson.put("namespace", Natives.Profile.Namespace.INHERITED.name)
    templateJson.put("uid", 0)
    templateJson.put("gid", 0)

    templateJson.put("groups", JSONArray().apply { put(Groups.INET.name) })
    templateJson.put("capabilities", JSONArray().apply { put(Capabilities.CAP_NET_RAW.name) })
    templateJson.put("context", "u:r:ksu:s0")
    templateJson.put("flags", JSONArray().apply { put(Natives.Profile.RootProfileFlag.NO_NEW_PRIVS.name) })
    Log.i(TAG, "$templateJson")
}
