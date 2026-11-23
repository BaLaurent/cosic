package com.octopusman.cosic.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRepositoryTest {
    private var clock = 1000L
    private fun repo(dir: File) = ProjectRepository(dir, { clock++ }, { "Sketch $it" }, { "Recovered $it" })
    private fun tmp() = kotlin.io.path.createTempDirectory("cosic").toFile()

    @Test fun createDefaultsAndLoad() {
        val r = repo(tmp())
        val p = r.create()
        assertEquals("Sketch 1", p.name)
        assertTrue(p.code.startsWith("// New sketch\nsetcpm(30)"))
        assertEquals(p, r.load(p.id))
        assertEquals("Sketch 2", r.create().name)
    }

    @Test fun listOrderedByUpdatedDescAndStateFlow() {
        val r = repo(tmp())
        val a = r.create("a", "x"); val b = r.create("b", "y")
        assertEquals(listOf(b.id, a.id), r.list().map { it.id })
        r.rename(a.id, "a2")
        assertEquals(listOf(a.id, b.id), r.projects.value.map { it.id })
        assertEquals("a2", r.projects.value.first().name)
    }

    @Test fun saveIsAtomicNoTmpLeft() {
        val d = tmp(); val r = repo(d)
        val p = r.create("a", "x")
        r.save(p.copy(code = "z"))
        assertEquals("z", r.load(p.id)?.code)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun corruptFileSkippedAndRenamed() {
        val d = tmp(); val r = repo(d)
        val p = r.create("a", "x")
        File(d, "bad.json").writeText("{nope")
        assertEquals(listOf(p.id), r.list().map { it.id })
        assertTrue(File(d, "bad.json.corrupt").exists())
        assertTrue(!File(d, "bad.json").exists())
    }

    @Test fun duplicateAndDelete() {
        val r = repo(tmp())
        val p = r.create("beat", "x")
        val c = r.duplicate(p.id)!!
        assertEquals("beat copy", c.name)
        assertEquals("x", c.code)
        r.delete(p.id)
        assertNull(r.load(p.id))
        assertEquals(1, r.list().size)
    }

    @Test fun damagedFilesAreCountedAndRecovered() {
        val d = tmp(); val r = repo(d)
        File(d, "x.json").writeText("broken")
        r.list()
        assertEquals(1, r.damaged.value)
        assertEquals(1, r.recoverDamaged())
        val recovered = r.list().single()
        assertEquals("Recovered 1", recovered.name)
        assertEquals("broken", recovered.code)
        assertEquals(0, r.damaged.value)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".corrupt") })
    }

    @Test fun renameOfUnknownIdIsFalse() {
        val r = repo(tmp())
        val p = r.create("a", "x")
        assertTrue(r.rename(p.id, "b"))
        assertEquals(false, r.rename("nope", "b"))
    }
}
