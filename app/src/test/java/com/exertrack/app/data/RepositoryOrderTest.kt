package com.exertrack.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryOrderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun workout(id: String, name: String, lastUsed: Long?) =
        Workout(id = id, name = name, rows = emptyList(), createdAt = 0, updatedAt = 0, lastUsedAt = lastUsed)

    @Test
    fun `moveWorkout puts the moved workout in the target's place in both directions`() {
        val repo = Repository(temp.newFolder())
        repo.load(now = 0)
        listOf("a", "b", "c", "d").forEach { repo.saveWorkout(workout(it, it.uppercase(), null), now = 0) }
        repo.deleteWorkout(repo.workouts.value.first { it.name == "Sample circuit" }.id)
        assertEquals(listOf("a", "b", "c", "d"), repo.workouts.value.map { it.id })

        repo.moveWorkout("a", "c") // down: a takes c's place
        assertEquals(listOf("b", "c", "a", "d"), repo.workouts.value.map { it.id })

        repo.moveWorkout("d", "b") // up: d takes b's place
        assertEquals(listOf("d", "b", "c", "a"), repo.workouts.value.map { it.id })

        repo.moveWorkout("d", "missing") // unknown ids are ignored
        repo.moveWorkout("d", "d")
        assertEquals(listOf("d", "b", "c", "a"), repo.workouts.value.map { it.id })
    }

    @Test
    fun `a duplicate lands right under its original`() {
        val repo = Repository(temp.newFolder())
        repo.load(now = 0)
        repo.deleteWorkout(repo.workouts.value.first().id)
        listOf("a", "b", "c").forEach { repo.saveWorkout(workout(it, it.uppercase(), null), now = 0) }
        val copy = repo.duplicateWorkout("a", now = 0)!!
        assertEquals(listOf("a", copy.id, "b", "c"), repo.workouts.value.map { it.id })
        assertEquals("A (2)", copy.name)
    }

    @Test
    fun `restoring an old backup applies the last-used order, a new one keeps its order`() {
        val repo = Repository(temp.newFolder())
        repo.load(now = 0)
        val old = listOf(workout("z", "Zed", 10), workout("a", "Alpha", null), workout("r", "Recent", 50))
        repo.replaceAll(old, emptyList(), Settings(), manuallyOrdered = false)
        assertEquals(listOf("r", "z", "a"), repo.workouts.value.map { it.id })
        repo.replaceAll(old, emptyList(), Settings(), manuallyOrdered = true)
        assertEquals(listOf("z", "a", "r"), repo.workouts.value.map { it.id })
    }

    @Test
    fun `the order survives a reload and the file is marked as manually ordered`() {
        val dir = temp.newFolder()
        val repo = Repository(dir)
        repo.load(now = 0)
        repo.deleteWorkout(repo.workouts.value.first().id)
        listOf("a", "b", "c").forEach { repo.saveWorkout(workout(it, it, null), now = 0) }
        repo.moveWorkout("c", "a")
        repo.flush()

        val text = File(dir, "workouts.json").readText()
        assertTrue(text.contains("\"orderVersion\": 1"))
        val again = Repository(dir)
        again.load(now = 0)
        assertEquals(listOf("c", "a", "b"), again.workouts.value.map { it.id })
    }

    @Test
    fun `a file from before manual ordering is sorted once by last use then name`() {
        val dir = temp.newFolder()
        val old = listOf(
            workout("zed", "Zed", lastUsed = 100),
            workout("beta", "beta", lastUsed = null),
            workout("alpha", "Alpha", lastUsed = null),
            workout("recent", "Recent", lastUsed = 500),
        )
        // Write the old document shape: no orderVersion field.
        val json = org.json.JSONObject(Json.workoutsDocument(old)).apply { remove("orderVersion") }.toString(2)
        assertTrue(!json.contains("orderVersion"))
        File(dir, "workouts.json").writeText(json)

        val repo = Repository(dir)
        repo.load(now = 0)
        assertEquals(listOf("recent", "zed", "alpha", "beta"), repo.workouts.value.map { it.id })
        repo.flush()
        assertTrue(File(dir, "workouts.json").readText().contains("\"orderVersion\": 1"))

        // A second load keeps the (now manual) order untouched even if usage changes.
        repo.touchWorkoutUsed("beta", at = 999)
        repo.flush()
        val again = Repository(dir)
        again.load(now = 0)
        assertEquals(listOf("recent", "zed", "alpha", "beta"), again.workouts.value.map { it.id })
    }
}
