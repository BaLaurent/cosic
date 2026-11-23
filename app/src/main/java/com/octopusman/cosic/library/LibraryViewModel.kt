package com.octopusman.cosic.library

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopusman.cosic.CosicApp
import java.io.IOException
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

    /** How many unreadable project files were set aside and not recovered yet. */
    val damaged: StateFlow<Int> = projects.damaged
    val examples: List<Example> = loadExamples(assets)
    val lessons: List<Lesson> = loadLessons(assets)

    /** Creates a sketch with the default code and returns its id; null when the project cannot be written (storage full). */
    suspend fun createProject(): String? = withContext(Dispatchers.IO) { writing { projects.create().id } }

    /** Creates a project named after the [example]'s title, holding its code, and returns its id; null when it cannot be written. */
    suspend fun createFromExample(example: Example): String? =
        withContext(Dispatchers.IO) { writing { projects.create(example.title, example.code).id } }

    /** Opens the lesson's project (made at step 0 the first time) and returns its id; null when it cannot be written. */
    suspend fun openLesson(lesson: Lesson): String? =
        withContext(Dispatchers.IO) { writing { projects.startLesson(lesson.id, lesson.title, lesson.steps.first()).id } }

    /** Puts the lesson back at its first step and returns its project's id; null when it cannot be written. */
    suspend fun restartLesson(lesson: Lesson): String? =
        withContext(Dispatchers.IO) { writing { projects.restartLesson(lesson.id, lesson.title, lesson.steps.first()).id } }

    /** Renames the project; false when it is gone or the change cannot be written. */
    suspend fun rename(id: String, name: String): Boolean =
        withContext(Dispatchers.IO) { writing { projects.rename(id, name) } ?: false }

    /** Copies the project; false when it is gone or the copy cannot be written. */
    suspend fun duplicate(id: String): Boolean =
        withContext(Dispatchers.IO) { writing { projects.duplicate(id) } != null }

    /** Makes projects of the set-aside files; false when one cannot be written. */
    suspend fun recoverDamaged(): Boolean =
        withContext(Dispatchers.IO) { writing { projects.recoverDamaged() } != null }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) { projects.delete(id) }
    }

    private inline fun <T> writing(block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        null
    }

    companion object {
        fun factory(app: CosicApp) = viewModelFactory {
            initializer { LibraryViewModel(app.projects, app.assets) }
        }
    }
}
