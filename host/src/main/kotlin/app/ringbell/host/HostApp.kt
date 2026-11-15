package app.ringbell.host

import android.app.Application

class HostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        History.init(this)
        Notifs.createChannels(this)
    }
}
