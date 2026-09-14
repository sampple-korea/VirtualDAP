package com.virtualdap.host.model

/** Mode and device selection are explicit. Neither transport may silently take over the other. */
object OutputRoutePolicy {
    fun eligible(route: OutputRoute, mode: OutputMode): Boolean = when (mode) {
        OutputMode.USB -> route.isUsb && route.directUsbDeviceId != null
        OutputMode.OFFICIAL_BIT_PERFECT -> route.directUsbDeviceId == null &&
            route.officialBitPerfectFormats.isNotEmpty() && route.officialQueryFailure == null
    }

    fun selected(state: PipelineSnapshot): OutputRoute? = state.availableRoutes.firstOrNull {
        it.id == state.selectedRouteId && eligible(it, state.outputMode)
    }
}
