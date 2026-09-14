package com.virtualdap.host.container

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Binding-only lifetime token for the process serving container package/activity APIs. */
class ContainerControlService : Service() {
    private val lifetime = Binder()
    override fun onBind(intent: Intent): IBinder = lifetime
}
