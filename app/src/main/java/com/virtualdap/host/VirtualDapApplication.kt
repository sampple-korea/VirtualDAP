package com.virtualdap.host

import android.app.Application
import android.content.Context
import com.virtualdap.host.container.ContainerRuntime

class VirtualDapApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ContainerRuntime.attach(base)
    }

    override fun onCreate() {
        super.onCreate()
        ContainerRuntime.onCreate()
    }
}
