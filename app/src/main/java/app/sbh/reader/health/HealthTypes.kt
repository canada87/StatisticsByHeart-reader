package app.sbh.reader.health

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import app.sbh.reader.R
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The type registry. One entry per Health Connect type: HC class, permission (derived from the class),
 * mapper to the wire DTO, and the official daily aggregates (§5.2.4).
 * Units are the contract's fixed units (api-contract.md §4); the mappers convert.
 */
object HealthTypes {
    /** Contract limits on variable-length payloads; anything beyond is dropped, never rejected. */
    const val MAX_SAMPLES = 20_000
    const val MAX_STAGES = 1_000
    const val MAX_ROUTE_POINTS = 50_000

    /** Reads every workout route without a per-session consent (declared in the manifest, granted in HC settings). */
    const val ROUTES_PERMISSION = "android.permission.health.READ_EXERCISE_ROUTES"

    /** What Health Connect says about the GPS route of a workout. */
    enum class RouteOutcome { DATA, CONSENT_REQUIRED, NO_DATA }

    /** Null for anything that is not a workout. An empty route counts as [RouteOutcome.NO_DATA]. */
    fun routeOutcome(record: Record): RouteOutcome? {
        val result = (record as? ExerciseSessionRecord)?.exerciseRouteResult ?: return null
        return when (result) {
            is ExerciseRouteResult.Data -> if (result.exerciseRoute.route.isNotEmpty()) RouteOutcome.DATA else RouteOutcome.NO_DATA
            is ExerciseRouteResult.ConsentRequired -> RouteOutcome.CONSENT_REQUIRED
            else -> RouteOutcome.NO_DATA
        }
    }

    /** True when [record] is a workout whose GPS route is readable now and not empty. */
    fun hasRoute(record: Record): Boolean = routeOutcome(record) == RouteOutcome.DATA

    val all: List<HealthTypeSpec> = listOf(
        // ---- Tier 1 (MVP) ----
        spec<StepsRecord>("Steps", 1, RecordShape.INTERVAL, MetricGroup.ACTIVITY, R.string.type_steps, R.drawable.ic_footprints,
            aggregates = listOf(sum(StepsRecord.COUNT_TOTAL) { it.toDouble() })) { scalar(it.count.toDouble()) },
        spec<HeartRateRecord>("HeartRate", 1, RecordShape.SERIES, MetricGroup.HEART, R.string.type_heart_rate, R.drawable.ic_heartbeat,
            aggregates = listOf(
                avg(HeartRateRecord.BPM_AVG) { it.toDouble() },
                min(HeartRateRecord.BPM_MIN) { it.toDouble() },
                max(HeartRateRecord.BPM_MAX) { it.toDouble() },
                count(HeartRateRecord.MEASUREMENTS_COUNT) { it.toDouble() },
            )) { r -> samples(r.samples.map { it.time.toString() to it.beatsPerMinute.toDouble() }) },
        spec<RestingHeartRateRecord>("RestingHeartRate", 1, RecordShape.INSTANT, MetricGroup.HEART, R.string.type_resting_heart_rate, R.drawable.ic_heart,
            aggregates = listOf(
                avg(RestingHeartRateRecord.BPM_AVG) { it.toDouble() },
                min(RestingHeartRateRecord.BPM_MIN) { it.toDouble() },
                max(RestingHeartRateRecord.BPM_MAX) { it.toDouble() },
            )) { scalar(it.beatsPerMinute.toDouble()) },
        spec<SleepSessionRecord>("SleepSession", 1, RecordShape.SESSION, MetricGroup.SLEEP, R.string.type_sleep_session, R.drawable.ic_moon,
            aggregates = listOf(sum(SleepSessionRecord.SLEEP_DURATION_TOTAL) { it.seconds() })) { r ->
            buildJsonObject {
                r.title?.let { put("title", it.take(200)) }
                putJsonArray("stages") {
                    r.stages.take(MAX_STAGES).forEach { s ->
                        add(buildJsonObject {
                            put("stage", SleepSessionRecord.STAGE_TYPE_INT_TO_STRING_MAP[s.stage] ?: "unknown")
                            put("start_time", s.startTime.toString())
                            put("end_time", s.endTime.toString())
                        })
                    }
                }
            }
        },
        spec<ExerciseSessionRecord>("ExerciseSession", 1, RecordShape.SESSION, MetricGroup.ACTIVITY, R.string.type_exercise_session, R.drawable.ic_barbell,
            aggregates = listOf(sum(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) { it.seconds() })) { r ->
            buildJsonObject {
                put("exercise_type", ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[r.exerciseType]?.uppercase() ?: "OTHER_WORKOUT")
                put("exercise_type_id", r.exerciseType)
                r.title?.let { put("title", it.take(200)) }
                // The route is readable only with READ_EXERCISE_ROUTES (or a per-session consent): otherwise omitted.
                (r.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.takeIf { it.route.isNotEmpty() }?.let { route ->
                    put("route", routeJson(route))
                }
            }
        },
        spec<ActiveCaloriesBurnedRecord>("ActiveCaloriesBurned", 1, RecordShape.INTERVAL, MetricGroup.ENERGY, R.string.type_active_calories, R.drawable.ic_fire,
            aggregates = listOf(sum(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inKilocalories })) { scalar(it.energy.inKilocalories) },
        spec<TotalCaloriesBurnedRecord>("TotalCaloriesBurned", 1, RecordShape.INTERVAL, MetricGroup.ENERGY, R.string.type_total_calories, R.drawable.ic_fire,
            aggregates = listOf(sum(TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it.inKilocalories })) { scalar(it.energy.inKilocalories) },
        spec<DistanceRecord>("Distance", 1, RecordShape.INTERVAL, MetricGroup.ACTIVITY, R.string.type_distance, R.drawable.ic_path,
            aggregates = listOf(sum(DistanceRecord.DISTANCE_TOTAL) { it.inMeters })) { scalar(it.distance.inMeters) },
        spec<WeightRecord>("Weight", 1, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_weight, R.drawable.ic_scales,
            aggregates = listOf(
                avg(WeightRecord.WEIGHT_AVG) { it.inKilograms },
                min(WeightRecord.WEIGHT_MIN) { it.inKilograms },
                max(WeightRecord.WEIGHT_MAX) { it.inKilograms },
            )) { scalar(it.weight.inKilograms) },
        spec<OxygenSaturationRecord>("OxygenSaturation", 1, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_oxygen_saturation, R.drawable.ic_wind) {
            scalar(it.percentage.value)
        },
        spec<HeartRateVariabilityRmssdRecord>("HeartRateVariabilityRmssd", 1, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_hrv, R.drawable.ic_lightning) {
            scalar(it.heartRateVariabilityMillis)
        },

        // ---- Tier 2 (off by default; the inventory tells whether they carry data) ----
        spec<SpeedRecord>("Speed", 2, RecordShape.SERIES, MetricGroup.ACTIVITY, R.string.type_speed, R.drawable.ic_lightning,
            aggregates = listOf(
                avg(SpeedRecord.SPEED_AVG) { it.inMetersPerSecond },
                min(SpeedRecord.SPEED_MIN) { it.inMetersPerSecond },
                max(SpeedRecord.SPEED_MAX) { it.inMetersPerSecond },
            )) { r -> samples(r.samples.map { it.time.toString() to it.speed.inMetersPerSecond }) },
        spec<PowerRecord>("Power", 2, RecordShape.SERIES, MetricGroup.ACTIVITY, R.string.type_power, R.drawable.ic_lightning,
            aggregates = listOf(
                avg(PowerRecord.POWER_AVG) { it.inWatts },
                min(PowerRecord.POWER_MIN) { it.inWatts },
                max(PowerRecord.POWER_MAX) { it.inWatts },
            )) { r -> samples(r.samples.map { it.time.toString() to it.power.inWatts }) },
        spec<StepsCadenceRecord>("StepsCadence", 2, RecordShape.SERIES, MetricGroup.ACTIVITY, R.string.type_steps_cadence, R.drawable.ic_footprints,
            aggregates = listOf(
                avg(StepsCadenceRecord.RATE_AVG) { it },
                min(StepsCadenceRecord.RATE_MIN) { it },
                max(StepsCadenceRecord.RATE_MAX) { it },
            )) { r -> samples(r.samples.map { it.time.toString() to it.rate }) },
        spec<CyclingPedalingCadenceRecord>("CyclingPedalingCadence", 2, RecordShape.SERIES, MetricGroup.ACTIVITY, R.string.type_cycling_cadence, R.drawable.ic_footprints,
            aggregates = listOf(
                avg(CyclingPedalingCadenceRecord.RPM_AVG) { it },
                min(CyclingPedalingCadenceRecord.RPM_MIN) { it },
                max(CyclingPedalingCadenceRecord.RPM_MAX) { it },
            )) { r -> samples(r.samples.map { it.time.toString() to it.revolutionsPerMinute }) },
        spec<FloorsClimbedRecord>("FloorsClimbed", 2, RecordShape.INTERVAL, MetricGroup.ACTIVITY, R.string.type_floors, R.drawable.ic_mountains,
            aggregates = listOf(sum(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { it })) { scalar(it.floors) },
        spec<ElevationGainedRecord>("ElevationGained", 2, RecordShape.INTERVAL, MetricGroup.ACTIVITY, R.string.type_elevation, R.drawable.ic_mountains,
            aggregates = listOf(sum(ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it.inMeters })) { scalar(it.elevation.inMeters) },
        spec<Vo2MaxRecord>("Vo2Max", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_vo2max, R.drawable.ic_wind) {
            scalar(it.vo2MillilitersPerMinuteKilogram)
        },
        spec<RespiratoryRateRecord>("RespiratoryRate", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_respiratory_rate, R.drawable.ic_wind) {
            scalar(it.rate)
        },
        spec<BasalMetabolicRateRecord>("BasalMetabolicRate", 2, RecordShape.INSTANT, MetricGroup.ENERGY, R.string.type_bmr, R.drawable.ic_fire,
            aggregates = listOf(sum(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL) { it.inKilocalories })) {
            scalar(it.basalMetabolicRate.inKilocaloriesPerDay)
        },
        spec<HeightRecord>("Height", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_height, R.drawable.ic_scales) { scalar(it.height.inMeters) },
        spec<BodyFatRecord>("BodyFat", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_body_fat, R.drawable.ic_scales) { scalar(it.percentage.value) },
        spec<LeanBodyMassRecord>("LeanBodyMass", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_lean_body_mass, R.drawable.ic_scales) { scalar(it.mass.inKilograms) },
        spec<BoneMassRecord>("BoneMass", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_bone_mass, R.drawable.ic_scales) { scalar(it.mass.inKilograms) },
        spec<BodyWaterMassRecord>("BodyWaterMass", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_body_water_mass, R.drawable.ic_drop) { scalar(it.mass.inKilograms) },
        spec<BloodPressureRecord>("BloodPressure", 2, RecordShape.INSTANT, MetricGroup.HEART, R.string.type_blood_pressure, R.drawable.ic_heart) {
            buildJsonObject {
                put("systolic", it.systolic.inMillimetersOfMercury)
                put("diastolic", it.diastolic.inMillimetersOfMercury)
            }
        },
        spec<BloodGlucoseRecord>("BloodGlucose", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_blood_glucose, R.drawable.ic_drop) {
            scalar(it.level.inMillimolesPerLiter)
        },
        spec<BodyTemperatureRecord>("BodyTemperature", 2, RecordShape.INSTANT, MetricGroup.VITALS, R.string.type_body_temperature, R.drawable.ic_thermometer) {
            scalar(it.temperature.inCelsius)
        },
        spec<SkinTemperatureRecord>("SkinTemperature", 2, RecordShape.SERIES, MetricGroup.VITALS, R.string.type_skin_temperature, R.drawable.ic_thermometer,
            requiredFeature = HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) { r ->
            buildJsonObject {
                put("samples", samplesArray(r.deltas.map { it.time.toString() to it.delta.inCelsius }))
                r.baseline?.let { put("baseline_celsius", it.inCelsius) }
            }
        },
        spec<HydrationRecord>("Hydration", 2, RecordShape.INTERVAL, MetricGroup.VITALS, R.string.type_hydration, R.drawable.ic_drop,
            aggregates = listOf(sum(HydrationRecord.VOLUME_TOTAL) { it.inLiters })) { scalar(it.volume.inLiters) },
        spec<NutritionRecord>("Nutrition", 2, RecordShape.INTERVAL, MetricGroup.ENERGY, R.string.type_nutrition, R.drawable.ic_fire,
            aggregates = listOf(sum(NutritionRecord.ENERGY_TOTAL) { it.inKilocalories })) { r ->
            buildJsonObject {
                r.name?.let { put("name", it.take(200)) }
                r.energy?.let { put("energy_kcal", it.inKilocalories) }
                r.protein?.let { put("protein_g", it.inGrams) }
                r.totalFat?.let { put("total_fat_g", it.inGrams) }
                r.totalCarbohydrate?.let { put("total_carbohydrate_g", it.inGrams) }
                r.dietaryFiber?.let { put("dietary_fiber_g", it.inGrams) }
                r.sugar?.let { put("sugar_g", it.inGrams) }
            }
        },
    )

    val byId: Map<String, HealthTypeSpec> = all.associateBy { it.id }
    val byClass: Map<kotlin.reflect.KClass<out Record>, HealthTypeSpec> = all.associateBy { it.recordClass }
    val defaultEnabledIds: Set<String> = all.filter { it.defaultEnabled }.map { it.id }.toSet()

    fun forRecord(record: Record): HealthTypeSpec? = byClass[record::class]

    // ---- builders ----

    private inline fun <reified R : Record> spec(
        id: String,
        tier: Int,
        shape: RecordShape,
        group: MetricGroup,
        label: Int,
        icon: Int,
        aggregates: List<AggregateField> = emptyList(),
        requiredFeature: Int? = null,
        crossinline data: (R) -> JsonObject,
    ) = HealthTypeSpec(
        id = id,
        recordClass = R::class,
        tier = tier,
        shape = shape,
        group = group,
        labelRes = label,
        iconRes = icon,
        aggregates = aggregates,
        requiredFeature = requiredFeature,
        dataMapper = { data(it as R) },
    )

    private fun <T : Any> field(kind: AggregateKind, metric: AggregateMetric<T>, conv: (T) -> Double) =
        AggregateField(kind, metric) {
            @Suppress("UNCHECKED_CAST")
            conv(it as T)
        }

    private fun <T : Any> sum(m: AggregateMetric<T>, conv: (T) -> Double) = field(AggregateKind.SUM, m, conv)
    private fun <T : Any> avg(m: AggregateMetric<T>, conv: (T) -> Double) = field(AggregateKind.AVG, m, conv)
    private fun <T : Any> min(m: AggregateMetric<T>, conv: (T) -> Double) = field(AggregateKind.MIN, m, conv)
    private fun <T : Any> max(m: AggregateMetric<T>, conv: (T) -> Double) = field(AggregateKind.MAX, m, conv)
    private fun <T : Any> count(m: AggregateMetric<T>, conv: (T) -> Double) = field(AggregateKind.COUNT, m, conv)

    private fun Duration.seconds(): Double = toMillis() / 1000.0

    private fun scalar(value: Double): JsonObject = buildJsonObject { put("value", value) }

    private fun samplesArray(points: List<Pair<String, Double>>): JsonArray = buildJsonArray {
        points.take(MAX_SAMPLES).forEach { (time, value) ->
            add(buildJsonObject {
                put("time", time)
                put("value", value)
            })
        }
    }

    private fun samples(points: List<Pair<String, Double>>): JsonObject =
        buildJsonObject { put("samples", samplesArray(points)) }

    private fun routeJson(route: ExerciseRoute): JsonArray = buildJsonArray {
        route.route.take(MAX_ROUTE_POINTS).forEach { p ->
            add(buildJsonObject {
                put("time", p.time.toString())
                put("lat", p.latitude)
                put("lon", p.longitude)
                p.altitude?.let { put("alt_m", it.inMeters) }
                p.horizontalAccuracy?.let { put("horizontal_accuracy_m", it.inMeters) }
            })
        }
    }
}
