package com.virtualdap.host.container

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Same-UID lifecycle events from hosted processes to the host's UI process. */
class ContainerEventProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Foreign container event caller" }
        if (method == "started" && !arg.isNullOrBlank()) {
            val pid = extras?.getInt("pid", -1) ?: -1
            require(pid > 0 && arg.length <= 255) { "Invalid container lifecycle event" }
            ContainerRuntime.appStarted(arg, pid)
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
