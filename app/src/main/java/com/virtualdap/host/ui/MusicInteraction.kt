package com.virtualdap.host.ui

import com.virtualdap.host.container.ContainerApp
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.model.OutputRoutePolicy
import com.virtualdap.host.model.PipelineSnapshot
import com.virtualdap.host.audio.DsdOutputMode

internal object MusicInteraction {
    fun matches(app: ContainerApp, query: String): Boolean = query.trim().let {
        it.isEmpty() || app.name.contains(it, ignoreCase = true) || app.packageName.contains(it, ignoreCase = true)
    }

    fun launchBlock(phase: ContainerPhase, app: ContainerApp, audio: PipelineSnapshot): String? = when {
        phase != ContainerPhase.READY -> "음악 공간 준비가 끝나면 실행할 수 있습니다."
        app.starting -> "앱을 여는 중입니다…"
        else -> null
    }

    fun shouldStartOutput(audio: PipelineSnapshot): Boolean = !audio.enabled &&
        !audio.dsdPlayback.active && audio.outputTestPhase != com.virtualdap.host.model.OutputTestPhase.RUNNING &&
        OutputRoutePolicy.selected(audio) != null

    fun bypassesSoftwareVolume(audio: PipelineSnapshot): Boolean =
        audio.dsdPlayback.active && audio.dsdPlayback.mode != DsdOutputMode.PCM_CONVERSION
}
