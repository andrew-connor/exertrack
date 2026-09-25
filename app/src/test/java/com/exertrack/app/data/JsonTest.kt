package com.exertrack.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    private fun workoutWithMedia(): Workout = Workout(
        id = "w1",
        name = "Legs",
        rounds = 2,
        leadInSec = 5,
        notes = "Warm up first.\nStretch after.",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        lastUsedAt = 3_000L,
        rows = listOf(
            ExerciseRow(id = "r1", name = "Squats", durationSec = 40, triggerSec = 15, notes = "Deep", media = RowMedia("abc.mp4", MediaKind.VIDEO)),
            ExerciseRow(id = "r2", name = "Plank", durationSec = null, triggerSec = null, notes = "", media = null),
            ExerciseRow(id = "r3", name = "Lunges", durationSec = 30, triggerSec = 10, media = RowMedia("pending/def.jpg", MediaKind.IMAGE)),
        ),
    )

    @Test
    fun `workout with notes and media round-trips`() {
        val original = workoutWithMedia()
        val parsed = Json.parseWorkoutsDocument(Json.workoutsDocument(listOf(original)))
        assertEquals(listOf(original), parsed)
    }

    @Test
    fun `row json carries the media object`() {
        val o = Json.row(workoutWithMedia().rows[0])
        val media = o.getJSONObject("media")
        assertEquals("abc.mp4", media.getString("file"))
        assertEquals("VIDEO", media.getString("kind"))
        assertTrue(Json.row(workoutWithMedia().rows[1]).isNull("media"))
    }

    @Test
    fun `thumb file name and pending flag derive from the file name`() {
        val video = RowMedia("abc.mp4", MediaKind.VIDEO)
        assertEquals("abc.thumb.jpg", video.thumbFile)
        assertFalse(video.isPending)

        val pending = RowMedia("pending/def.jpg", MediaKind.IMAGE)
        assertEquals("pending/def.thumb.jpg", pending.thumbFile)
        assertTrue(pending.isPending)
    }

    @Test
    fun `unknown media kind parses to null media`() {
        val row = JSONObject()
            .put("id", "r9")
            .put("name", "Burpees")
            .put("media", JSONObject().put("file", "x.gif").put("kind", "ANIMATION"))
        assertNull(Json.row(row).media)
    }

    @Test
    fun `media without a file name parses to null media`() {
        val row = JSONObject()
            .put("name", "Burpees")
            .put("media", JSONObject().put("file", "").put("kind", "IMAGE"))
        assertNull(Json.row(row).media)
    }

    @Test
    fun `missing media and notes keys parse to defaults`() {
        val row = JSONObject().put("id", "r1").put("name", "Squats").put("durationSec", 40)
        val parsedRow = Json.row(row)
        assertNull(parsedRow.media)
        assertEquals("", parsedRow.notes)

        val explicitNull = JSONObject().put("name", "Plank").put("media", JSONObject.NULL)
        assertNull(Json.row(explicitNull).media)

        val workout = JSONObject()
            .put("id", "w1")
            .put("name", "Legs")
            .put("rows", org.json.JSONArray().put(row))
        val parsedWorkout = Json.workout(workout)
        assertEquals("", parsedWorkout.notes)
        assertEquals(1, parsedWorkout.rows.size)
        assertEquals(40, parsedWorkout.rows[0].durationSec)
    }

    @Test
    fun `older workouts document without notes or media still loads`() {
        val text = """
            {"schemaVersion":1,"workouts":[
              {"id":"w1","name":"Old","rows":[{"id":"r1","name":"Jog","durationSec":60,"triggerSec":null,"notes":"Easy"}],
               "rounds":1,"leadInSec":3,"createdAt":1,"updatedAt":2,"lastUsedAt":null,"sourceUri":null,"sourceSheet":null}
            ]}
        """.trimIndent()
        val workouts = Json.parseWorkoutsDocument(text)
        assertEquals(1, workouts.size)
        assertEquals("", workouts[0].notes)
        assertEquals("Easy", workouts[0].rows[0].notes)
        assertNull(workouts[0].rows[0].media)
    }
}
