package app.sbh.reader.fakes

import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic Health Connect records for tests (never real data, PROJECT_GUIDE §7). */
object TestRecords {
    const val FIT = "com.google.android.apps.fitness"
    const val WITHINGS = "com.withings.wiscale2"
    val CEST: ZoneOffset = ZoneOffset.ofHours(2)

    fun metadata(
        id: String,
        modified: Instant = Instant.parse("2026-09-01T00:00:00Z"),
        pkg: String = FIT,
        device: Device? = null,
        method: Int = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
    ): Metadata = METADATA_CTOR.newInstance(method, id, DataOrigin(pkg), modified, null, 0L, device)

    // Metadata's full constructor is internal to Health Connect; tests need control over lastModifiedTime.
    private val METADATA_CTOR = Metadata::class.java.getConstructor(
        Int::class.javaPrimitiveType, String::class.java, DataOrigin::class.java, Instant::class.java,
        String::class.java, Long::class.javaPrimitiveType, Device::class.java,
    )

    fun steps(
        id: String,
        start: Instant,
        count: Long = 100,
        minutes: Long = 15,
        modified: Instant = Instant.parse("2026-09-01T00:00:00Z"),
        pkg: String = FIT,
    ) = StepsRecord(start, CEST, start.plusSeconds(minutes * 60), CEST, count, metadata(id, modified, pkg))

    fun weight(id: String, time: Instant, kg: Double = 72.5, pkg: String = WITHINGS) =
        WeightRecord(time, CEST, Mass.kilograms(kg), metadata(id, pkg = pkg, device = Device(Device.TYPE_SCALE, "Withings", "Body+")))

    fun heartRate(id: String, start: Instant, bpms: List<Long>) = HeartRateRecord(
        start, CEST, start.plusSeconds(bpms.size * 60L), CEST,
        bpms.mapIndexed { i, b -> HeartRateRecord.Sample(start.plusSeconds(i * 60L), b) },
        metadata(id),
    )

    /** A workout; [routePoints] > 0 attaches a synthetic route (readable), 0 = no data, null = consent required. */
    fun exercise(
        id: String,
        start: Instant,
        minutes: Long = 30,
        routePoints: Int? = 0,
        modified: Instant = Instant.parse("2026-09-01T00:00:00Z"),
    ): ExerciseSessionRecord = ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = CEST,
        endTime = start.plusSeconds(minutes * 60),
        endZoneOffset = CEST,
        metadata = metadata(id, modified),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        title = "Camminata",
        exerciseRouteResult = routeResult(start, routePoints),
    )

    // Points are 1 ms apart so that even the capped 50k-point route stays inside the session (HC validates it).
    fun routeResult(start: Instant, points: Int?): ExerciseRouteResult = when {
        points == null -> ExerciseRouteResult.ConsentRequired()
        points == 0 -> ExerciseRouteResult.NoData()
        else -> ExerciseRouteResult.Data(
            ExerciseRoute(
                List(points) { i ->
                    ExerciseRoute.Location(time = start.plusMillis(i.toLong()), latitude = 45.0 + i * 1e-6, longitude = 9.0 + i * 1e-6)
                },
            ),
        )
    }

    fun sleep(id: String, start: Instant, end: Instant) = SleepSessionRecord(
        start, CEST, end, CEST, metadata(id), "Notte", null,
        listOf(
            SleepSessionRecord.Stage(start, start.plusSeconds(3600), SleepSessionRecord.STAGE_TYPE_LIGHT),
            SleepSessionRecord.Stage(start.plusSeconds(3600), end, SleepSessionRecord.STAGE_TYPE_DEEP),
        ),
    )
}
