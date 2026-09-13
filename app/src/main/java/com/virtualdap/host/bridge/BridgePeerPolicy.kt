package com.virtualdap.host.bridge

/**
 * Kernel credentials are checked before parsing attacker-controlled bytes. A containerized
 * Audio HAL normally appears as root, system or audioserver; same-UID is useful for integration
 * tests and runtimes that map all guest processes onto the owning host application UID.
 */
class BridgePeerPolicy(
    private val hostUid: Int,
    private val trustedRuntimeUid: () -> Int? = { null },
) {
    fun isAllowed(peerUid: Int): Boolean = peerUid == hostUid || peerUid == trustedRuntimeUid() ||
        peerUid in TRUSTED_GUEST_UIDS

    companion object {
        private val TRUSTED_GUEST_UIDS = setOf(
            0, // root-backed container runtime
            1000, // Android system
            1041, // Android audioserver / audio HAL service
        )
    }
}
