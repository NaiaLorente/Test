package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Injects a canned assistant message (e.g. a character's opening greeting) into the model's
     * context/history without generating it, so the model has a concrete in-character example to
     * imitate from the very first turn.
     */
    suspend fun seedAssistantMessage(message: String)

    /**
     * Injects a canned user message into the model's context/history without generating a reply,
     * used to replay a persisted conversation after the app restarts.
     */
    suspend fun seedUserMessage(message: String)

    /**
     * Injects a system-role note (e.g. a saved rolling-summary recap) into context/history
     * without generating it - the mid-conversation counterpart to [setSystemPrompt], which only
     * ever sets the pinned persona message. Used to replay a previously saved [compactedHistory]
     * snapshot.
     */
    suspend fun seedSystemNote(note: String)

    /**
     * The model's current chat history after the pinned system message - any rolling-summary
     * recap notes folded in by context shifting, plus the still-live raw turns - as a JSON array
     * of {"role", "content"} objects, in order. Lets a caller snapshot a bounded picture of
     * native memory (never larger than what actually still fits in context) so a later cold-start
     * replay can resume from this instead of re-seeding the entire, ever-growing raw transcript
     * from scratch every time. "[]" if no system prompt has been processed yet.
     */
    suspend fun compactedHistory(): String

    /**
     * The pinned system/persona message's own end position - where the raw conversation history
     * begins. Needed alongside a [compactedHistory] snapshot to later resume bookkeeping after a
     * [loadContextState] restore. 0 if no system prompt has been processed yet.
     */
    suspend fun systemPromptPosition(): Int

    /**
     * Persists the context's raw internal state (KV-cache contents, sampler state) to [path], so
     * a later [loadContextState] can restore it directly instead of reprocessing the system
     * prompt and conversation from scratch - the actual fix for a cold start on a large system
     * prompt/long conversation taking a very long time. A save is a real disk write proportional
     * to how much context is in use, so callers should pick a deliberate cadence (e.g. only when
     * leaving a chat) rather than after every message.
     */
    suspend fun saveContextState(path: String)

    /**
     * Restores the context's raw internal state previously saved by [saveContextState]. Returns
     * false, rather than throwing, if there's simply nothing valid to restore - no state was ever
     * saved, the file is missing/corrupt, or it doesn't match the currently loaded model - since
     * that's an expected, non-exceptional outcome callers should just fall back to a normal replay
     * for, not treat as a failure. Only restores the raw state itself; [restoreContext] still needs
     * to run afterwards to rebuild the higher-level bookkeeping the rest of the engine relies on.
     */
    suspend fun loadContextState(path: String): Boolean

    /**
     * Rebuilds bookkeeping (chat_msgs, positions) to match a context whose raw state was just
     * restored via a successful [loadContextState] - the saved state blob itself carries no
     * notion of "messages", only raw cache contents. Must be called right after, before any other
     * engine operation, with the exact [systemPrompt]/[systemPromptPosition] and [entries] that
     * were live at save time (from [compactedHistory]/[systemPromptPosition]) - never a freshly
     * rebuilt system prompt, which would no longer match what's actually in the restored cache.
     */
    suspend fun restoreContext(systemPrompt: String, systemPromptPosition: Int, entries: List<RestoredHistoryEntry>)

    /**
     * Adjusts the sampler's "creativity" (temperature). Safe to call any time a model is loaded;
     * takes effect on the next generated reply.
     */
    suspend fun setTemperature(temperature: Float)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Human-readable speed summary for the reply [sendUserPrompt] most recently completed, e.g.
     * "42 tokens in 18.3s (2.30 tok/s)". Empty until the first reply finishes.
     */
    fun lastReplyStats(): String

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Signals the currently in-flight [sendUserPrompt] generation, if any, to stop after its
     * current token - the collector's flow simply completes with whatever was generated so far,
     * rather than throwing or needing to be torn down. Safe to call even if nothing is generating.
     */
    fun cancelGeneration()

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        // Kept short on purpose: long replies from small phone-class models tend to ramble into
        // fabricated/generic padding the longer they run, which reads as robotic rather than
        // human (real dialogue is punchy, not multi-paragraph). Shorter also means faster,
        // directly cutting wait time on slower/bigger models since generation time scales with
        // token count. Cut further from 180 - even within that cap, replies were still drifting
        // into rambling tangents and self-contradiction the longer a single reply ran, so this
        // trades a little more length for the model having less room to wander per turn.
        const val DEFAULT_PREDICT_LENGTH = 120
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()

/** One [InferenceEngine.compactedHistory] entry, as needed to rebuild bookkeeping via [InferenceEngine.restoreContext]. */
data class RestoredHistoryEntry(val role: String, val content: String, val endPosition: Int)
