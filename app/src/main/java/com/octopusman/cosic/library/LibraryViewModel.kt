package com.octopusman.cosic.library

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopusman.cosic.CosicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The project list and the actions on it. Every change goes through the repository's disk I/O off the main thread. */
class LibraryViewModel(
    private val projects: ProjectRepository,
    assets: AssetManager,
) : ViewModel() {
    val projectList: StateFlow<List<Project>> = projects.projects
    val examples: List<Example> = loadExamples(assets)

    /** Creates a sketch with the default code and returns its id. */
    suspend fun createProject(): String = withContext(Dispatchers.IO) { projects.create().id }

    /** Creates a project named after the [example]'s title, holding its code, and returns its id. */
    suspend fun createFromExample(example: Example): String =
        withContext(Dispatchers.IO) { projects.create(example.title, example.code).id }

    fun rename(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) { projects.rename(id, name) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch(Dispatchers.IO) { projects.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) { projects.delete(id) }
    }

    companion object {
        fun factory(app: CosicApp) = viewModelFactory {
            initializer { LibraryViewModel(app.projects, app.assets) }
        }
    }
}
