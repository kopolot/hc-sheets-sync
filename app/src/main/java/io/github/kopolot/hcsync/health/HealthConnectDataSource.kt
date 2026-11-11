package io.github.kopolot.hcsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
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
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.kopolot.hcsync.domain.BurnRecord
import io.github.kopolot.hcsync.domain.DayAggregate
import io.github.kopolot.hcsync.domain.HealthSnapshot
import io.github.kopolot.hcsync.domain.Measurement
import io.github.kopolot.hcsync.domain.SleepSession
import io.github.kopolot.hcsync.domain.TimeInterval
import io.github.kopolot.hcsync.domain.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectDataSource(
    private val context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : HealthDataSource {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun status(): HcStatus {
        val availability = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.UPDATE_REQUIRED
            else -> HcAvailability.UNAVAILABLE
        }
        if (availability != HcAvailability.AVAILABLE) return HcStatus(availability)
        val features = client.features
        return HcStatus(
            availability = availability,
            granted = client.permissionController.getGrantedPermissions(),
            backgroundFeature = features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
            historyFeature = features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
        )
    }

    override suspend fun read(from: LocalDate, toInclusive: LocalDate): HealthSnapshot {
        val granted = client.permissionController.getGrantedPermissions()
        fun has(type: KClass<out Record>) = Permissions.read(type) in granted
        val z = zone()

        // Raw window is widened by a day on each side: sleep that ends on `from` starts the day before,
        // and records with their own zone offset may fall on a neighbouring device-local day.
        // The aggregator only emits days inside the requested range.
        val rawStart = from.minusDays(1).atStartOfDay(z).toInstant()
        val rawEnd = toInclusive.plusDays(2).atStartOfDay(z).toInstant()
        val skipped = mutableListOf<String>()
        suspend fun <T : Record> raw(type: KClass<T>): List<T> =
            readAll(type, rawStart, rawEnd) { skipped += it }

        return HealthSnapshot(
            aggregates = aggregates(from, toInclusive, granted),
            weights = if (has(WeightRecord::class)) raw(WeightRecord::class).map {
                Measurement(it.time, it.weight.inKilograms, it.zoneOffset)
            } else emptyList(),
            bodyFat = if (has(BodyFatRecord::class)) raw(BodyFatRecord::class).map {
                Measurement(it.time, it.percentage.value, it.zoneOffset)
            } else emptyList(),
            sleep = if (has(SleepSessionRecord::class)) raw(SleepSessionRecord::class).map { s ->
                SleepSession(
                    start = s.startTime,
                    end = s.endTime,
                    awakeStages = s.stages.filter { it.stage in AWAKE_STAGES }
                        .map { TimeInterval(it.startTime, it.endTime) },
                    endOffset = s.endZoneOffset,
                )
            } else emptyList(),
            workouts = if (has(ExerciseSessionRecord::class)) raw(ExerciseSessionRecord::class).map {
                WorkoutSession(
                    id = it.metadata.id,
                    type = ExerciseTypes.name(it.exerciseType),
                    title = it.title,
                    start = it.startTime,
                    end = it.endTime,
                    startOffset = it.startZoneOffset,
                )
            } else emptyList(),
            burnRecords = if (has(TotalCaloriesBurnedRecord::class) && has(ActiveCaloriesBurnedRecord::class)) {
                raw(TotalCaloriesBurnedRecord::class).map {
                    BurnRecord(it.startTime, it.endTime, it.startZoneOffset, it.endZoneOffset)
                } + raw(ActiveCaloriesBurnedRecord::class).map {
                    BurnRecord(it.startTime, it.endTime, it.startZoneOffset, it.endZoneOffset)
                }
            } else null,
            skipped = skipped,
        )
    }

    private suspend fun aggregates(from: LocalDate, toInclusive: LocalDate, granted: Set<String>): List<DayAggregate> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (Permissions.read(NutritionRecord::class) in granted) addAll(NUTRITION_METRICS)
            if (Permissions.read(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (Permissions.read(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (Permissions.read(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (Permissions.read(RestingHeartRateRecord::class) in granted) add(RestingHeartRateRecord.BPM_AVG)
        }
        if (metrics.isEmpty()) return emptyList()

        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(
                    from.atStartOfDay(),
                    toInclusive.plusDays(1).atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        return groups.map { g -> g.result.toDayAggregate(g.startTime.toLocalDate()) }
    }

    override suspend fun diagnose(date: LocalDate): DayDiagnostics {
        val granted = client.permissionController.getGrantedPermissions()
        val z = zone()
        val localFilter = TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
        val rawStart = date.atStartOfDay(z).toInstant()
        val rawEnd = date.plusDays(1).atStartOfDay(z).toInstant()
        val notes = mutableListOf<String>()
        val metrics = mutableListOf<MetricDiagnostics>()
        val aggOrigins = mutableSetOf<String>()

        suspend fun <T : Record> compare(
            label: String,
            type: KClass<T>,
            metric: AggregateMetric<*>,
            aggValue: (AggregationResult) -> Double?,
            rawValue: (T) -> Double?,
        ) {
            if (Permissions.read(type) !in granted) {
                notes += "$label: brak zgody"
                return
            }
            val agg = client.aggregate(AggregateRequest(setOf(metric), localFilter))
            aggOrigins += agg.dataOrigins.map { it.packageName }
            val records = readAll(type, rawStart, rawEnd) { notes += it }
            val byOrigin = records.groupBy { it.metadata.dataOrigin.packageName }
                .mapValues { (_, rs) -> rs.sumOf { rawValue(it) ?: 0.0 } }
            metrics += MetricDiagnostics(label, aggValue(agg), byOrigin.values.sum(), byOrigin, records.size)
        }

        compare("kcal_in", NutritionRecord::class, NutritionRecord.ENERGY_TOTAL,
            { it[NutritionRecord.ENERGY_TOTAL]?.inKilocalories }, { it.energy?.inKilocalories })
        compare("protein_g", NutritionRecord::class, NutritionRecord.PROTEIN_TOTAL,
            { it[NutritionRecord.PROTEIN_TOTAL]?.inGrams }, { it.protein?.inGrams })
        compare("kcal_burned_total", TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            { it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories }, { it.energy.inKilocalories })
        compare("kcal_active", ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
            { it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories }, { it.energy.inKilocalories })
        compare("steps", StepsRecord::class, StepsRecord.COUNT_TOTAL,
            { it[StepsRecord.COUNT_TOTAL]?.toDouble() }, { it.count.toDouble() })

        // HC's total = TotalCalories records where they exist, otherwise ActiveCalories records + its own BMR estimate.
        val total = metrics.find { it.metric == "kcal_burned_total" }
        val active = metrics.find { it.metric == "kcal_active" }
        if (total?.aggregate != null && active != null) {
            notes += if (total.rawCount == 0) {
                "kcal_burned_total: brak rekordów total, więc agregat = surowe active + szacunek BMR HC " +
                    "≈ %.0f kcal BMR".format(total.aggregate - active.rawTotal) +
                    if (active.rawCount == 0) " (brak też rekordów active: cała wartość to BMR)." else "."
            } else {
                "kcal_burned_total: są rekordy total (%d); ile BMR HC doliczył, nie da się tu wiarygodnie rozdzielić.".format(total.rawCount)
            }
        }
        notes += "Surowe = prosta suma rekordów zaczynających się tego dnia, bez deduplikacji. " +
            "Agregat HC dzieli rekordy na granicy dnia i stosuje priorytety źródeł."
        return DayDiagnostics(date, metrics, aggOrigins, notes)
    }

    /**
     * Reads all pages of [type] in [start, end). A malformed record makes the HC library throw for the
     * whole page, so on [IllegalArgumentException] the window is split and the bad hour is skipped
     * (reported through [onSkipped]). Records spanning a split point come back twice, hence distinctBy id.
     */
    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        onSkipped: (String) -> Unit,
    ): List<T> {
        val result = readSplitting(start, end) { from, to ->
            val out = mutableListOf<T>()
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = token)
                )
                out += resp.records
                token = resp.pageToken
            } while (!token.isNullOrEmpty())
            out
        }
        result.skipped.forEach {
            onSkipped("Pominięto uszkodzony rekord ${type.simpleName} w oknie ${it.start} – ${it.end}")
        }
        return if (result.skipped.isEmpty()) result.items else result.items.distinctBy { it.metadata.id }
    }

    private fun AggregationResult.toDayAggregate(date: LocalDate) = DayAggregate(
        date = date,
        kcalIn = this[NutritionRecord.ENERGY_TOTAL]?.inKilocalories,
        proteinG = this[NutritionRecord.PROTEIN_TOTAL]?.inGrams,
        fatG = this[NutritionRecord.TOTAL_FAT_TOTAL]?.inGrams,
        carbsG = this[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.inGrams,
        kcalBurnedTotal = this[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
        kcalActive = this[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
        steps = this[StepsRecord.COUNT_TOTAL],
        restingHr = this[RestingHeartRateRecord.BPM_AVG],
    )

    private companion object {
        val NUTRITION_METRICS = setOf(
            NutritionRecord.ENERGY_TOTAL,
            NutritionRecord.PROTEIN_TOTAL,
            NutritionRecord.TOTAL_FAT_TOTAL,
            NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL,
        )
        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
    }
}
