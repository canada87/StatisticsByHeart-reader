package app.sbh.reader.fakes

import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import app.sbh.reader.health.AggregateKind
import app.sbh.reader.health.ChangeItem
import app.sbh.reader.health.ChangesPage
import app.sbh.reader.health.DailyAggregate
import app.sbh.reader.health.HcFeature
import app.sbh.reader.health.HealthRepository
import app.sbh.reader.health.HealthTypeSpec
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.health.RecordPage
import app.sbh.reader.health.SdkStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** In-memory Health Connect: records per type, a global change log (tokens are log positions) and scripted failures. */
class FakeHealthRepository : HealthRepository {
    var sdk = SdkStatus.AVAILABLE
    val granted: MutableSet<String> = HealthTypes.all.map { it.readPermission }.toMutableSet()
    val expiredTokens = mutableSetOf<String>()
    val failReadFor = mutableSetOf<String>()
    var changesPageSize = Int.MAX_VALUE

    private val store = mutableMapOf<String, MutableList<Record>>()
    private val log = mutableListOf<ChangeItem>()
    var tokensCreated = 0
        private set

    fun insert(record: Record) {
        val spec = HealthTypes.forRecord(record)!!
        val list = store.getOrPut(spec.id) { mutableListOf() }
        list.removeAll { it.metadata.id == record.metadata.id }
        list.add(record)
        log.add(ChangeItem.Upsert(record))
    }

    /** Pre-existing data (no change-log entry): what the phone holds before the app's token is created. */
    fun seed(record: Record) {
        val spec = HealthTypes.forRecord(record)!!
        store.getOrPut(spec.id) { mutableListOf() }.add(record)
    }

    /** What HC would hand out: a workout's route is only readable with READ_EXERCISE_ROUTES, otherwise consent is required. */
    private fun visible(record: Record): Record {
        if (record !is ExerciseSessionRecord || HealthTypes.ROUTES_PERMISSION in granted) return record
        if (record.exerciseRouteResult !is ExerciseRouteResult.Data) return record
        return TestRecords.exerciseWithRoute(
            record.startTime, record.endTime, record.metadata, record.exerciseType, record.title, record.notes,
            record.segments, record.laps, ExerciseRouteResult.ConsentRequired(),
        )
    }

    fun delete(id: String) {
        store.values.forEach { l -> l.removeAll { it.metadata.id == id } }
        log.add(ChangeItem.Delete(id))
    }

    override fun sdkStatus() = sdk
    override suspend fun grantedPermissions(): Set<String> = granted.toSet()
    override suspend fun isFeatureAvailable(feature: HcFeature) = true
    override suspend fun unsupportedTypeIds(): Set<String> = emptySet()

    override suspend fun readRecords(spec: HealthTypeSpec, from: Instant, to: Instant, pageToken: String?, pageSize: Int): RecordPage {
        if (spec.id in failReadFor) throw IllegalStateException("simulated HC failure")
        val matching = store[spec.id].orEmpty()
            .filter { r ->
                val start = spec.startOf(r)
                val end = spec.endOf(r)
                if (end == null) !start.isBefore(from) && start.isBefore(to) else start.isBefore(to) && end.isAfter(from)
            }
            .sortedBy { spec.startOf(it) }
        val offset = pageToken?.toInt() ?: 0
        val page = matching.drop(offset).take(pageSize).map { visible(it) }
        val next = offset + page.size
        return RecordPage(page, if (next < matching.size) next.toString() else null)
    }

    override suspend fun createChangesToken(specs: Collection<HealthTypeSpec>): String {
        tokensCreated++
        return "${log.size}|${specs.map { it.id }.sorted().joinToString(",")}|$tokensCreated"
    }

    override suspend fun readChanges(token: String): ChangesPage {
        if (token in expiredTokens) return ChangesPage(emptyList(), token, hasMore = false, tokenExpired = true)
        val parts = token.split('|')
        val pos = parts[0].toInt()
        val types = parts[1].split(',').toSet()
        val relevant = log.drop(pos)
        val page = relevant.take(changesPageSize)
        val kept = page.filter { c ->
            when (c) {
                is ChangeItem.Upsert -> HealthTypes.forRecord(c.record)!!.id in types
                is ChangeItem.Delete -> true
            }
        }.map { c -> if (c is ChangeItem.Upsert) ChangeItem.Upsert(visible(c.record)) else c }
        return ChangesPage(kept, "${pos + page.size}|${parts[1]}|${parts[2]}", hasMore = relevant.size > page.size, tokenExpired = false)
    }

    override suspend fun aggregateRange(spec: HealthTypeSpec, from: Instant, to: Instant): Map<AggregateKind, Double> = emptyMap()

    override suspend fun dailyAggregates(spec: HealthTypeSpec, fromDay: LocalDate, toDayExclusive: LocalDate): List<DailyAggregate> {
        if (spec.aggregates.isEmpty()) return emptyList()
        val kind = spec.aggregates.first().kind
        return store[spec.id].orEmpty()
            .groupBy { spec.startOf(it).atOffset(ZoneOffset.UTC).toLocalDate() }
            .filterKeys { !it.isBefore(fromDay) && it.isBefore(toDayExclusive) }
            .map { (day, list) -> DailyAggregate(day, spec.id, mapOf(kind to list.size.toDouble()), setOf(TestRecords.FIT)) }
    }
}
