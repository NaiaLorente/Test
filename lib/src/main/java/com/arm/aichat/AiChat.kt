package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl

/**
 * Name of the file, under [Context.getFilesDir], that a native crash (if one happens) appends
 * best-effort diagnostics to. Callers can check for this file on startup to surface a previous
 * crash's details to the user, since there is otherwise no way to retrieve a native crash's
 * cause without a PC and adb.
 */
const val NATIVE_CRASH_LOG_FILE_NAME = "native_crash_log.txt"

/**
 * Main entry point for Arm's AI Chat library.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
