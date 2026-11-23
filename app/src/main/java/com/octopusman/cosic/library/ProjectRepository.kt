package com.octopusman.cosic.library

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Project(
    val id: String,
    val name: String,
    val code: String,
    val createdAt: Long,
    val updatedAt: Long,
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
    fun create(name: String? = null, code: String = NEW_PROJECT_CODE): Project {
        val t = now()
        val project = Project(
            UUID.randomUUID().toString(),
            name ?: newName(list().size + 1),
            code, t, t,
        )
        save(project)
        return project
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
