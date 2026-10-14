package app.sbh.reader.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sbh.reader.data.SettingsRepository
import app.sbh.reader.health.AggregateKind
import app.sbh.reader.health.HealthAccess
import app.sbh.reader.health.HealthAccessState
import app.sbh.reader.health.HealthRepository
import app.sbh.reader.health.HealthTypeSpec
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.health.InventoryScanner
import app.sbh.reader.health.SdkStatus
import app.sbh.reader.health.TypeInventory
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.sync.TypeProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** "Today" mini-summary read straight from Health Connect (aesthetic touch, §8.6). Null = not available. */
data class TodaySummary(
    val steps: Long? = null,
    val hrAvg: Long? = null,
    val hrMin: Long? = null,
    val hrMax: Long? = null,
    val sleepSeconds: Long? = null,
    val loaded: Boolean = false,
)

data class TypeRow(
    val spec: HealthTypeSpec,
    val enabled: Boolean,
    val granted: Boolean,
    val supported: Boolean,
    val progress: TypeProgress?,
)

data class DataUiState(
    val rows: List<TypeRow> = emptyList(),
    val hcAvailable: Boolean = true,
    val today: TodaySummary = TodaySummary(),
)

class DataViewModel(
    private val settings: SettingsRepository,
    private val access: HealthAccess,
    private val health: HealthRepository,
    store: SyncStore,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val today = MutableStateFlow(TodaySummary())

    val state: StateFlow<DataUiState> = combine(
        settings.settings, access.state, store.typeProgressAll(), today,
    ) { s, hc, progress, today ->
        val byId = progress.associateBy { it.typeId }
        DataUiState(
            rows = HealthTypes.all.map { spec ->
                TypeRow(
                    spec = spec,
                    enabled = spec.id in s.enabledTypeIds,
                    granted = hc.isGranted(spec),
                    supported = hc.isSupported(spec),
                    progress = byId[spec.id],
                )
            },
            hcAvailable = hc.sdk == SdkStatus.AVAILABLE,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataUiState())

    fun setEnabled(typeId: String, enabled: Boolean) {
        viewModelScope.launch { settings.setTypeEnabled(typeId, enabled) }
    }

    fun refreshToday() {
        viewModelScope.launch {
            val hc = access.state.value
            if (hc.sdk != SdkStatus.AVAILABLE) return@launch
            val zone = ZoneId.systemDefault()
            val now = clock()
            val midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val sleepFrom = now.atZone(zone).toLocalDate().minusDays(1).atTime(18, 0).atZone(zone).toInstant()

            suspend fun agg(id: String, from: Instant): Map<AggregateKind, Double>? {
                val spec = HealthTypes.byId.getValue(id)
                if (!hc.isGranted(spec)) return null
                return try {
                    health.aggregateRange(spec, from, now)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }

            val steps = agg("Steps", midnight)
            val hr = agg("HeartRate", midnight)
            val sleep = agg("SleepSession", sleepFrom)
            today.value = TodaySummary(
                steps = steps?.get(AggregateKind.SUM)?.toLong(),
                hrAvg = hr?.get(AggregateKind.AVG)?.toLong(),
                hrMin = hr?.get(AggregateKind.MIN)?.toLong(),
                hrMax = hr?.get(AggregateKind.MAX)?.toLong(),
                sleepSeconds = sleep?.get(AggregateKind.SUM)?.toLong(),
                loaded = true,
            )
        }
    }
}

sealed interface InventoryState {
    data object Idle : InventoryState
    data class Scanning(val currentTypeId: String, val readSoFar: Long, val done: Int, val total: Int) : InventoryState
    data object Done : InventoryState
}

data class InventoryUiState(
    val state: InventoryState = InventoryState.Idle,
    val results: List<TypeInventory> = emptyList(),
    val scannable: List<HealthTypeSpec> = emptyList(),
    val notGranted: List<HealthTypeSpec> = emptyList(),
    val hc: HealthAccessState = HealthAccessState(),
)

class InventoryViewModel(
    private val access: HealthAccess,
    health: HealthRepository,
) : ViewModel() {
    private val scanner = InventoryScanner(health)
    private val scan = MutableStateFlow<InventoryState>(InventoryState.Idle)
    private val results = MutableStateFlow<List<TypeInventory>>(emptyList())
    private var job: Job? = null

    val state: StateFlow<InventoryUiState> = combine(scan, results, access.state) { s, r, hc ->
        val supported = HealthTypes.all.filter { hc.isSupported(it) }
        InventoryUiState(
            state = s,
            results = r,
            scannable = supported.filter { hc.isGranted(it) },
            notGranted = supported.filter { !hc.isGranted(it) },
            hc = hc,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    fun start() {
        if (job?.isActive == true) return
        val targets = state.value.scannable
        if (targets.isEmpty()) return
        results.value = emptyList()
        job = viewModelScope.launch {
            targets.forEachIndexed { index, spec ->
                scan.value = InventoryState.Scanning(spec.id, 0, index, targets.size)
                val inv = scanner.scan(spec) { n ->
                    scan.update { cur -> if (cur is InventoryState.Scanning) cur.copy(readSoFar = n) else cur }
                }
                results.update { it + inv }
            }
            scan.value = InventoryState.Done
        }
    }

    fun cancel() {
        job?.cancel()
        scan.value = if (results.value.isEmpty()) InventoryState.Idle else InventoryState.Done
    }

    /** Plain-text summary (counts and dates only, never values) for sharing with the developer / agents. */
    fun report(labelOf: (String) -> String): String = buildString {
        appendLine("Inventario Health Connect - ${results.value.size} tipi")
        results.value.filter { it.total > 0 }.sortedByDescending { it.total }.forEach { inv ->
            appendLine("${inv.typeId} (${labelOf(inv.typeId)}): ${inv.total} record, ${inv.first?.toString()?.take(10)} -> ${inv.last?.toString()?.take(10)}${if (inv.truncated) " [parziale]" else ""}")
            inv.sources.forEach { s ->
                appendLine("   - ${s.packageName}: ${s.count} record, ${s.first?.toString()?.take(10)} -> ${s.last?.toString()?.take(10)}")
            }
        }
        val empty = results.value.filter { it.total == 0L }.map { it.typeId }
        if (empty.isNotEmpty()) appendLine("Senza dati: ${empty.joinToString(", ")}")
    }
}
