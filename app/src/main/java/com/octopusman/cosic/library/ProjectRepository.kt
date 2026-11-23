package com.octopusman.cosic.library

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which bundled lesson a project follows ([id]: the lesson's file name without `.cosic`) and the step it is at, from 0. */
@Serializable
data class LessonProgress(val id: String, val step: Int)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val code: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lesson: LessonProgress? = null,
)

const val NEW_PROJECT_CODE = "// New sketch\nsetcpm(30)\n\$: s(\"bd ~ sd ~\")\n"

/**
 * The projects, one JSON file each in [dir]. A name for a new project and for a recovered one comes from [newName] / [recoveredName] (they take a number
 * from 1), so that the app can have them in its resources.
 */
class ProjectRepository(
    private val dir: File,
    private val now: () -> Long,
    private val newName: (Int) -> String,
    private val recoveredName: (Int) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = state
    private val damagedState = MutableStateFlow(0)

    /** How many unreadable project files were set aside (renamed `.json.corrupt`) and not recovered yet. */
    val damaged: StateFlow<Int> = damagedState

    init {
        dir.mkdirs()
        refresh()
    }

    @Synchronized
    fun list(): List<Project> = refresh()

    @Synchronized
    fun load(id: String): Project? {
        val file = fileOf(id)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<Project>(file.readText())
        } catch (e: Exception) {
            file.renameTo(File(dir, "$id.json.corrupt"))
            null
        }
    }

    @Synchronized
    fun save(project: Project) {
        dir.mkdirs()
        val tmp = File(dir, "${project.id}.json.tmp")
        // on disk before the rename: otherwise a power loss can keep the rename and lose the bytes, leaving an empty project
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(Project.serializer(), project).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(fileOf(project.id))) {
            tmp.delete()
            throw java.io.IOException("Could not save project ${project.id}")
        }
        refresh()
    }

    @Synchronized
    fun create(name: String? = null, code: String = NEW_PROJECT_CODE, lesson: LessonProgress? = null): Project {
        val t = now()
        val project = Project(
            UUID.randomUUID().toString(),
            name ?: newName(list().count { it.lesson == null } + 1),
            code, t, t, lesson,
        )
        save(project)
        return project
    }

    /** The project that follows the lesson [lessonId] (the most recently updated one), or null when the lesson was not started. */
    @Synchronized
    fun lessonProject(lessonId: String): Project? = list().firstOrNull { it.lesson?.id == lessonId }

    /** The project of the lesson [lessonId]: the one already started, else a new one named [name], at step 0, holding [firstStep]. */
    @Synchronized
    fun startLesson(lessonId: String, name: String, firstStep: String): Project =
        lessonProject(lessonId) ?: create(name, firstStep, LessonProgress(lessonId, 0))

    /** Puts the lesson [lessonId] back at step 0 with [firstStep] as its code (in a new project when it was not started) and returns its project. */
    @Synchronized
    fun restartLesson(lessonId: String, name: String, firstStep: String): Project {
        val existing = lessonProject(lessonId) ?: return create(name, firstStep, LessonProgress(lessonId, 0))
        val reset = existing.copy(code = firstStep, lesson = LessonProgress(lessonId, 0), updatedAt = now())
        save(reset)
        return reset
    }

    /** Renames the project; false when there is no (readable) project with that id. */
    @Synchronized
    fun rename(id: String, name: String): Boolean {
        val p = load(id) ?: return false
        save(p.copy(name = name, updatedAt = now()))
        return true
    }

    @Synchronized
    fun duplicate(id: String): Project? {
        val p = load(id) ?: return null
        val t = now()
        val copy = Project(UUID.randomUUID().toString(), "${p.name} copy", p.code, t, t)
        save(copy)
        return copy
    }

    @Synchronized
    fun delete(id: String) {
        fileOf(id).delete()
        refresh()
    }

    /**
     * Makes a new project of the text of every set-aside file (oldest first, named by [recoveredName]) and removes the file. Returns how many there were;
     * an [java.io.IOException] from saving a project goes to the caller, the files not yet done stay.
     */
    @Synchronized
    fun recoverDamaged(): Int {
        val files = damagedFiles().sortedBy { it.lastModified() }
        files.forEachIndexed { i, file ->
            create(recoveredName(i + 1), file.readText())
            file.delete()
        }
        refresh()
        return files.size
    }

    private fun damagedFiles(): List<File> = (dir.listFiles { f -> f.name.endsWith(".json.corrupt") } ?: emptyArray()).toList()

    private fun fileOf(id: String) = File(dir, "$id.json")

    private fun refresh(): List<Project> {
        val all = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { load(it.name.removeSuffix(".json")) }
            .sortedByDescending { it.updatedAt }
        state.value = all
        damagedState.value = damagedFiles().size
        return all
    }
}
