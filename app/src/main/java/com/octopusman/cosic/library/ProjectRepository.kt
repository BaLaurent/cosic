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

class ProjectRepository(private val dir: File, private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = state

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
        tmp.writeText(json.encodeToString(Project.serializer(), project))
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
            name ?: "Sketch ${list().size + 1}",
            code, t, t,
        )
        save(project)
        return project
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name, updatedAt = now()))
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

    private fun fileOf(id: String) = File(dir, "$id.json")

    private fun refresh(): List<Project> {
        val all = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { load(it.name.removeSuffix(".json")) }
            .sortedByDescending { it.updatedAt }
        state.value = all
        return all
    }
}
