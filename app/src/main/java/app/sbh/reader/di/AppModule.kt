package app.sbh.reader.di

import android.app.Application
import android.os.Build
import app.sbh.reader.BuildConfig
import app.sbh.reader.data.AppSyncSettings
import app.sbh.reader.data.RoomSyncStore
import app.sbh.reader.data.SettingsRepository
import app.sbh.reader.data.SyncDatabase
import app.sbh.reader.health.HealthAccess
import app.sbh.reader.health.HealthConnectRepository
import app.sbh.reader.health.HealthRepository
import app.sbh.reader.net.DeviceIdentity
import app.sbh.reader.net.HttpSbhApi
import app.sbh.reader.net.SbhApi
import app.sbh.reader.net.ServerConfig
import app.sbh.reader.security.KeystoreTokenStore
import app.sbh.reader.security.TokenStore
import app.sbh.reader.sync.SyncEngine
import app.sbh.reader.sync.SyncSettings
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.system.NetworkMonitor
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.config.ConfigViewModel
import app.sbh.reader.ui.config.GuideViewModel
import app.sbh.reader.ui.config.LogViewModel
import app.sbh.reader.ui.data.DataViewModel
import app.sbh.reader.ui.data.InventoryViewModel
import app.sbh.reader.ui.link.LinkViewModel
import app.sbh.reader.work.RouteRefreshWorker
import app.sbh.reader.work.SyncNotifier
import app.sbh.reader.work.SyncRunner
import app.sbh.reader.work.SyncScheduler
import app.sbh.reader.work.SyncStatusHolder
import app.sbh.reader.work.SyncWorker
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

val appModule = module {
    single { SettingsRepository(androidContext()) }
    single<TokenStore> { KeystoreTokenStore(androidContext()) }
    single { SyncDatabase.create(androidContext()) }
    single<SyncStore> { RoomSyncStore(get()) }
    single<HealthRepository> { HealthConnectRepository(androidContext()) }
    single { HealthAccess(get()) }
    single { NetworkMonitor(androidContext()) }
    single<SyncSettings> { AppSyncSettings(get(), get()) }

    single {
        val settings = get<SettingsRepository>()
        DeviceIdentityProvider {
            DeviceIdentity(
                deviceId = settings.deviceId(),
                name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                model = Build.MODEL,
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
    single<SbhApi> {
        val settings = get<SettingsRepository>()
        val tokens = get<TokenStore>()
        val identity = get<DeviceIdentityProvider>()
        HttpSbhApi(
            deviceProvider = { identity.get() },
            configProvider = { ServerConfig(settings.current().serverUrl, tokens.get()) },
        )
    }
    single { SbhApiFactory(get()) }

    single { SyncEngine(health = get(), store = get(), api = get(), settings = get()) }
    single { SyncStatusHolder() }
    single { SyncRunner(get(), get()) }
    single { SyncNotifier(androidContext()) }
    single { SyncScheduler(androidContext()) }

    worker { SyncWorker(androidContext(), get(), get(), get()) }
    worker { RouteRefreshWorker(androidContext(), get(), get(), get()) }

    viewModel { MainViewModel(get(), get(), get()) }
    viewModel { LinkViewModel(get<Application>(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { DataViewModel(get(), get(), get(), get()) }
    viewModel { InventoryViewModel(get(), get()) }
    viewModel { LogViewModel(get()) }
    viewModel { ConfigViewModel(get(), get(), get(), get(), get<SbhApiFactory>()::create) }
    viewModel { GuideViewModel(get()) }
}

/** Wraps the suspend lookup of the device identity so Koin can hand it around as a typed singleton. */
class DeviceIdentityProvider(private val block: suspend () -> DeviceIdentity) {
    suspend fun get(): DeviceIdentity = block()
}

/** Builds a client for explicit (possibly unsaved) values: used by "Test connection". */
class SbhApiFactory(private val identity: DeviceIdentityProvider) {
    fun create(config: ServerConfig): SbhApi = HttpSbhApi({ identity.get() }, { config })
}
