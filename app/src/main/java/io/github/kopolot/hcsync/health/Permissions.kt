package io.github.kopolot.hcsync.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

object Permissions {
    /** Record types we read, with a human label for the UI. */
    val dataTypes: List<Pair<KClass<out Record>, String>> = listOf(
        NutritionRecord::class to "Żywienie",
        TotalCaloriesBurnedRecord::class to "Kalorie spalone (total)",
        ActiveCaloriesBurnedRecord::class to "Kalorie aktywne",
        ExerciseSessionRecord::class to "Treningi",
        WeightRecord::class to "Waga",
        BodyFatRecord::class to "Tkanka tłuszczowa",
        StepsRecord::class to "Kroki",
        RestingHeartRateRecord::class to "Tętno spoczynkowe",
        SleepSessionRecord::class to "Sen",
    )

    fun read(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

    val data: Set<String> = dataTypes.map { read(it.first) }.toSet()

    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    const val HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    val extra: Set<String> = setOf(BACKGROUND, HISTORY)
}
