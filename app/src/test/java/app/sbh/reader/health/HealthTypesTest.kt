package app.sbh.reader.health

import app.sbh.reader.fakes.TestRecords
import app.sbh.reader.fakes.TestRecords.heartRate
import app.sbh.reader.fakes.TestRecords.sleep
import app.sbh.reader.fakes.TestRecords.steps
import app.sbh.reader.fakes.TestRecords.weight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class HealthTypesTest {
    private val t0 = Instant.parse("2026-10-14T06:00:00Z")

    // ------------------------------------------------------------ registry

    @Test
    fun idsAreUniqueAndTier1MatchesTheGuide() {
        assertEquals(HealthTypes.all.size, HealthTypes.all.map { it.id }.toSet().size)
        assertEquals(
            setOf(
                "Steps", "HeartRate", "RestingHeartRate", "SleepSession", "ExerciseSession", "ActiveCaloriesBurned",
                "TotalCaloriesBurned", "Distance", "Weight", "OxygenSaturation", "HeartRateVariabilityRmssd",
            ),
            HealthTypes.defaultEnabledIds,
        )
    }

    @Test
    fun timeAccessorsResolveForEveryRegisteredClass() {
        // HealthTypeSpec resolves them by reflection at construction: reaching this line means all of them exist.
        assertTrue(HealthTypes.all.isNotEmpty())
        HealthTypes.all.forEach { spec ->
            val expectsEnd = spec.shape != RecordShape.INSTANT
            assertEquals("${spec.id} end accessor", expectsEnd, runCatching { spec.recordClass.java.getMethod("getEndTime") }.isSuccess)
        }
    }

    @Test
    fun manifestDeclaresEveryPermissionOfTheRegistry() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("android:name=\"(android\\.permission\\.health\\.[A-Z0-9_]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = HealthTypes.all.map { it.readPermission }.filter { it !in declared }
        assertTrue("permissions missing in AndroidManifest.xml: $missing", missing.isEmpty())
        assertTrue("history permission declared", "android.permission.health.READ_HEALTH_DATA_HISTORY" in declared)
        assertTrue("background permission declared", "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" in declared)
    }

    // ------------------------------------------------------------ mappers (contract §4)

    @Test
    fun stepsIsAnIntervalWithTheEnvelopeFieldsOfTheContract() {
        val out = HealthTypes.byId.getValue("Steps").map(steps("hc-1", t0, count = 842, minutes = 15))

        assertEquals("Steps", out.type)
        assertEquals("hc-1", out.hcId)
        assertEquals("2026-10-14T06:00:00Z", out.startTime)
        assertEquals("2026-10-14T06:15:00Z", out.endTime)
        assertEquals(7200, out.startOffsetS)
        assertEquals(7200, out.endOffsetS)
        assertEquals(TestRecords.FIT, out.source.packageName)
        assertEquals("automatic", out.source.recordingMethod)
        assertEquals("2026-09-01T00:00:00Z", out.lastModified)
        assertEquals(842.0, out.data["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(setOf("value"), out.data.keys)
    }

    @Test
    fun instantaneousRecordsHaveNoEndTimeAndConvertToContractUnits() {
        val out = HealthTypes.byId.getValue("Weight").map(weight("w-1", t0, kg = 72.5))

        assertNull(out.endTime)
        assertNull(out.endOffsetS)
        assertEquals(72.5, out.data["value"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("SCALE", out.source.device?.type)
        assertEquals("Withings", out.source.device?.manufacturer)
        assertEquals(TestRecords.WITHINGS, out.source.packageName)
    }

    @Test
    fun heartRateSeriesBecomesSamplesWithTimeAndValue() {
        val out = HealthTypes.byId.getValue("HeartRate").map(heartRate("hr-1", t0, listOf(60, 62, 65)))

        val samples: JsonArray = out.data["samples"]!!.jsonArray
        assertEquals(3, samples.size)
        assertEquals("2026-10-14T06:01:00Z", samples[1].jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals(62.0, samples[1].jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals("2026-10-14T06:03:00Z", out.endTime)
    }

    @Test
    fun sleepSessionCarriesNamedStages() {
        val out = HealthTypes.byId.getValue("SleepSession").map(sleep("sl-1", t0, t0.plusSeconds(7200)))

        val stages = out.data["stages"]!!.jsonArray
        assertEquals(listOf("light", "deep"), stages.map { it.jsonObject["stage"]!!.jsonPrimitive.content })
        assertEquals("Notte", out.data["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun mappingIsIdempotent() {
        val record = steps("hc-1", t0)
        val spec = HealthTypes.byId.getValue("Steps")
        assertEquals(spec.map(record), spec.map(record))
    }

    @Test
    fun seriesAreCappedAtTheContractLimit() {
        val out = HealthTypes.byId.getValue("HeartRate")
            .map(heartRate("hr-big", t0, List(HealthTypes.MAX_SAMPLES + 50) { 70L }))
        assertEquals(HealthTypes.MAX_SAMPLES, out.data["samples"]!!.jsonArray.size)
    }

    @Test
    fun dataObjectsOnlyContainContractFields() {
        // the writer rejects unknown fields: every Tier 1 mapper output must stay inside the documented set
        val allowed = mapOf(
            "Steps" to setOf("value"), "Weight" to setOf("value"),
            "HeartRate" to setOf("samples"), "SleepSession" to setOf("title", "stages"),
        )
        val outputs = listOf(
            steps("a", t0), weight("b", t0), heartRate("c", t0, listOf(60)), sleep("d", t0, t0.plusSeconds(7200)),
        ).map { rec -> HealthTypes.forRecord(rec)!!.let { it.id to it.map(rec).data } }
        for ((id, data: JsonObject) in outputs) assertTrue("$id has unexpected fields ${data.keys}", allowed.getValue(id).containsAll(data.keys))
    }
}
