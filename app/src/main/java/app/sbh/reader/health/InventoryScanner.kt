package app.sbh.reader.health

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Inventory of one type: how many records, from which apps, first/last. No values, only counts and dates. */
data class TypeInventory(
    val typeId: String,
    val total: Long,
    val first: Instant?,
    val last: Instant?,
    val sources: List<SourceStat>,
    /** True when reading stopped early (error/cap): counts are lower bounds. */
    val truncated: Boolean = false,
    val error: Boolean = false,
)

/**
 * Phase-0 / "scrematura" tool (PROJECT_GUIDE §2, §11): scans Health Connect per type and per source app.
 * Reads full pages, so it can take a while on large histories; always cancellable.
 */
class InventoryScanner(
    private val health: HealthRepository,
    private val clock: () -> Instant = Instant::now,
    private val pageSize: Int = 1000,
    private val maxRecordsPerType: Long = 2_000_000,
) {
    suspend fun scan(
        spec: HealthTypeSpec,
        onProgress: (readSoFar: Long) -> Unit = {},
    ): TypeInventory {
        val bySource = HashMap<String, Acc>()
        var total = 0L
        var first: Instant? = null
        var last: Instant? = null
        var token: String? = null
        var truncated = false
        try {
            val to = clock()
            do {
                currentCoroutineContext().ensureActive()
                val page = health.readRecords(spec, Instant.EPOCH, to, token, pageSize)
                for (r in page.records) {
                    val t = spec.startOf(r)
                    val acc = bySource.getOrPut(r.metadata.dataOrigin.packageName) { Acc() }
                    acc.count++
                    if (acc.first == null || t.isBefore(acc.first)) acc.first = t
                    if (acc.last == null || t.isAfter(acc.last)) acc.last = t
                    if (first == null || t.isBefore(first)) first = t
                    if (last == null || t.isAfter(last)) last = t
                }
                total += page.records.size
                onProgress(total)
                token = page.nextPageToken
                if (total >= maxRecordsPerType && token != null) {
                    truncated = true
                    break
                }
            } while (token != null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return TypeInventory(spec.id, total, first, last, sources(bySource), truncated = true, error = true)
        }
        return TypeInventory(spec.id, total, first, last, sources(bySource), truncated)
    }

    private fun sources(map: Map<String, Acc>) =
        map.map { (pkg, a) -> SourceStat(pkg, a.count, a.first, a.last) }.sortedByDescending { it.count }

    private class Acc {
        var count = 0L
        var first: Instant? = null
        var last: Instant? = null
    }
}
