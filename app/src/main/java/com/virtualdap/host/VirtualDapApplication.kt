package com.virtualdap.host

import android.app.Application
import com.virtualdap.host.guest.GuestRuntimeController

class VirtualDapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GuestRuntimeController.initialize(this)
    }
}
