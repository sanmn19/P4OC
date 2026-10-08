package dev.blazelight.p4oc

import android.app.Application
import dev.blazelight.p4oc.core.connection.ConnectionForegroundCoordinator
import dev.blazelight.p4oc.core.log.CrashRecorder
import dev.blazelight.p4oc.core.notification.NotificationEventObserver
import dev.blazelight.p4oc.di.allModules
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class PocketCodeApp : Application() {

    private val notificationEventObserver: NotificationEventObserver by inject()
    private val connectionForegroundCoordinator: ConnectionForegroundCoordinator by inject()

    override fun onCreate() {
        super.onCreate()

        CrashRecorder.install(this)
        startKoin {
            androidLogger()
            androidContext(this@PocketCodeApp)
            modules(allModules)
        }

        notificationEventObserver.start()
        connectionForegroundCoordinator.start()
    }
}
