package io.github.kopolot.hcsync.net

import io.github.kopolot.hcsync.domain.DailySummary
import io.github.kopolot.hcsync.domain.Workout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PayloadTest {

    private fun encode(days: List<DailySummary>) = PayloadJson.parseToJsonElement(
        PayloadJson.encodeToString(
            SyncPayload.serializer(),
            SyncPayload(secret = "s3cret", appVersion = "0.1.0", days = days.map { it.toDto() }),
        )
    ).jsonObject

    @Test
    fun `envelope has secret, schema and days`() {
        val json = encode(listOf(DailySummary(LocalDate.parse("2026-11-10"))))
        assertEquals("s3cret", json["secret"]!!.jsonPrimitive.content)
        assertEquals(PAYLOAD_SCHEMA, json["schema"]!!.jsonPrimitive.int)
        assertEquals("0.1.0", json["app_version"]!!.jsonPrimitive.content)
        assertEquals(1, json["days"]!!.jsonArray.size)
    }

    @Test
    fun `missing values are explicit nulls with snake_case keys`() {
        val day = encode(listOf(DailySummary(LocalDate.parse("2026-11-10")))).getValue("days").jsonArray[0].jsonObject
        val expectedKeys = setOf(
            "date", "kcal_in", "protein_g", "fat_g", "carbs_g", "kcal_burned_total", "kcal_active",
            "steps", "resting_hr", "weight_kg", "body_fat_pct", "sleep_min", "workouts",
        )
        assertEquals(expectedKeys, day.keys)
        assertEquals("2026-11-10", day["date"]!!.jsonPrimitive.content)
        expectedKeys.minus(setOf("date", "workouts")).forEach { assertTrue(it, day[it] is JsonNull) }
        assertTrue(day["workouts"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `workouts are serialized with ISO instants`() {
        val w = Workout("id-1", "strength_training", null,
            Instant.parse("2026-11-10T06:00:00Z"), Instant.parse("2026-11-10T07:02:00Z"), 62)
        val day = encode(listOf(DailySummary(LocalDate.parse("2026-11-10"), kcalIn = 2000, workouts = listOf(w))))
            .getValue("days").jsonArray[0].jsonObject
        assertEquals(2000, day["kcal_in"]!!.jsonPrimitive.int)
        val wj = day["workouts"]!!.jsonArray[0].jsonObject
        assertEquals("id-1", wj["id"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10T06:00:00Z", wj["start"]!!.jsonPrimitive.content)
        assertEquals(62, wj["min"]!!.jsonPrimitive.int)
        assertTrue(wj["title"] is JsonNull)
    }
}
