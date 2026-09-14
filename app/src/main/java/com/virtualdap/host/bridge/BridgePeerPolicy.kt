package com.virtualdap.host.bridge

/** Only the host application's real kernel UID may submit container PCM. */
class BridgePeerPolicy(private val hostUid: Int) {
    fun isAllowed(peerUid: Int): Boolean = peerUid == hostUid
}
