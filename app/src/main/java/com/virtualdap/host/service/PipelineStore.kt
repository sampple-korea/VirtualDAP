package com.virtualdap.host.service

import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.PipelineLog
import com.virtualdap.host.model.PipelineSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object PipelineStore {
    private const val MAX_LOGS = 80
    private val mutableState = MutableStateFlow(PipelineSnapshot())
    val state: StateFlow<PipelineSnapshot> = mutableState.asStateFlow()

    fun update(block: (PipelineSnapshot) -> PipelineSnapshot) = mutableState.update(block)

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        update { current ->
            current.copy(
                logs = (current.logs + PipelineLog(System.currentTimeMillis(), level, message))
                    .takeLast(MAX_LOGS),
            )
        }
    }
}
