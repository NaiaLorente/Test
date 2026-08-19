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
        // token count.
        const val DEFAULT_PREDICT_LENGTH = 180
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
