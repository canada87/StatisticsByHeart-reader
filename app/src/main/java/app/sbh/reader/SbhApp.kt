package app.sbh.reader

import android.app.Application
import app.sbh.reader.di.appModule
import app.sbh.reader.work.SyncNotifier
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.android.ext.android.inject

class SbhApp : Application() {
    private val notifier: SyncNotifier by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SbhApp)
            workManagerFactory()
            modules(appModule)
        }
        notifier.ensureChannel()
    }
}
