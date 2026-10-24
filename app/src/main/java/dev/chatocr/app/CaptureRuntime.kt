package dev.chatocr.app

import kotlinx.coroutines.flow.MutableStateFlow

data class CaptureStatus(val active: Boolean = false, val sessionId: String? = null, val recording: Boolean = false,
    val count: Int = 0, val frames: Int = 0, val pending: Int = 0, val hint: String = "", val finishing: Boolean = false)
object CaptureRuntime { val state = MutableStateFlow(CaptureStatus()) }
