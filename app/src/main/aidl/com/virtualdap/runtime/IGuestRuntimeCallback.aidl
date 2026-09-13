package com.virtualdap.runtime;

/** State callbacks from the platform-owned full Android guest runtime. */
oneway interface IGuestRuntimeCallback {
    void onStateChanged(int state, String detail);
}
