package com.charchat.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.RestoredHistoryEntry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Chat screen for a single character. Loads that character's persisted conversation from disk
 * and replays it into the model's actual context (not just the UI) so nothing is lost when the
 * app is closed and reopened.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var headerAvatarTile: View
    private lateinit var headerAvatar: ShapeableImageView
    private lateinit var headerAvatarLetter: TextView
    private lateinit var headerName: TextView
    private lateinit var statusTv: TextView
    private lateinit var stopGenerationButton: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: TextInputEditText
    private lateinit var sendFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private lateinit var character: Character
    // Deliberately never cancelled on onStop(): generation can take minutes on this hardware, and
    // backgrounding the app (switching apps, locking the screen, a notification) used to silently
    // discard it. lifecycleScope only cancels on true onDestroy(), so leaving this alone lets a
    // reply keep generating while the screen is merely stopped, and still stops cleanly once the
    // conversation is actually left (back button, or this Activity being destroyed for any other
    // reason). This can't survive the OS killing the whole process outright under memory pressure -
    // that would need a foreground service to prevent, which is a bigger change than this fix.
    private var generationJob: Job? = null
    private var isReady = false
    private var characterAvatarBitmap: Bitmap? = null

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(
        messages,
        onRegenerateLast = { regenerateLastReply() },
        onEditLastUser = { startEditingLastUserMessage() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)
        findViewById<View>(R.id.main).applySystemBarInsetsAsPadding()

        val characterId = intent.getStringExtra(EXTRA_CHARACTER_ID)
        if (characterId == null) {
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit_character -> {
                    editCharacter.launch(
                        Intent(this, CharacterSetupActivity::class.java)
                            .putExtra(CharacterSetupActivity.EXTRA_EDIT_CHARACTER_JSON, character.toJson().toString())
                    )
                    true
                }
                R.id.action_clear_conversation -> {
                    confirmClearConversation()
                    true
                }
                else -> false
            }
        }

        headerAvatarTile = findViewById(R.id.header_avatar_tile)
        headerAvatar = findViewById(R.id.header_avatar)
        headerAvatarLetter = findViewById(R.id.header_avatar_letter)
        headerName = findViewById(R.id.header_name)
        statusTv = findViewById(R.id.status_tv)
        stopGenerationButton = findViewById(R.id.stop_generation_button)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        sendFab = findViewById(R.id.fab)

        sendFab.setOnClickListener { if (isReady) handleUserInput() }
        headerAvatarTile.setOnClickListener { characterAvatarBitmap?.let { showFullscreenAvatar(it) } }

        val loaded = ConversationStore.loadMessages(this, characterId)
        val loadedCharacter = ConversationStore.listCharacters(this).find { it.id == characterId }
        if (loadedCharacter == null) {
            finish()
            return
        }
        character = loadedCharacter
        messages.addAll(loaded)
        messageAdapter.notifyDataSetChanged()
        applyHeader()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
            }
            if (engine.state.value !is InferenceEngine.State.ModelReady) {
                // Cold restore without going through the gallery first: bounce back there so the
                // model gets loaded properly instead of assuming it already is.
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@ChatActivity, CharacterGalleryActivity::class.java))
                    finish()
                }
                return@launch
            }
            replayConversation()
        }
    }

    /**
     * Saves the model's actual in-memory state (KV-cache) to disk whenever this screen stops
     * being visible - backgrounded, or left entirely - so the *next* time it's opened, cold-start
     * replay can restore it directly instead of reprocessing the whole system prompt and
     * conversation from scratch (see attemptFastContextRestore() in replayConversation()). Chosen
     * deliberately over saving after every message: a save is a real disk write proportional to
     * how much context is in use, so doing it only here avoids adding that cost to every single
     * exchange while still keeping the save reasonably fresh.
     */
    override fun onStop() {
        super.onStop()
        if (::character.isInitialized && ::engine.isInitialized && isReady) {
            val coveredMessageCount = messages.size
            lifecycleScope.launch(Dispatchers.Default) {
                runCatching { saveContextStateSnapshot(coveredMessageCount) }
                    .onFailure { Log.w(TAG, "Failed to save context state", it) }
            }
        }
    }

    private suspend fun saveContextStateSnapshot(coveredMessageCount: Int) {
        val modelName = ModelStorage.activeModelName(this) ?: return
        engine.saveContextState(ConversationStore.contextStateFile(this, character.id).path)
        ConversationStore.saveContextStateMetadata(
            context = this,
            characterId = character.id,
            modelName = modelName,
            systemPromptHash = character.toSystemPrompt().hashCode().toString(),
            systemPromptPosition = engine.systemPromptPosition(),
            historyJson = engine.compactedHistory(),
            coveredMessageCount = coveredMessageCount
        )
    }

    /**
     * Tries to restore the model's actual memory from a previously saved raw context state
     * ([saveContextStateSnapshot]) instead of reprocessing the system prompt and conversation from
     * scratch - the fix for a cold start on a large system prompt/long conversation taking minutes.
     * Returns false (never throws) for any reason it can't: no state was ever saved, it doesn't
     * match the currently active model, the character was edited since (different system prompt
     * hash), or the file is missing/corrupt - so the caller can fall back to a normal replay
     * exactly as if this function didn't exist.
     */
    private suspend fun attemptFastContextRestore(): Boolean = runCatching {
        val metadata = ConversationStore.loadContextStateMetadata(this, character.id) ?: return@runCatching false
        if (metadata.coveredMessageCount !in 1..messages.size) return@runCatching false
        if (metadata.modelName != ModelStorage.activeModelName(this)) return@runCatching false
        val systemPrompt = character.toSystemPrompt()
        if (metadata.systemPromptHash != systemPrompt.hashCode().toString()) return@runCatching false

        val stateFile = ConversationStore.contextStateFile(this, character.id)
        if (!engine.loadContextState(stateFile.path)) return@runCatching false

        val restoredEntries = metadata.entries.map { RestoredHistoryEntry(it.role, it.content, it.endPosition) }
        engine.restoreContext(systemPrompt, metadata.systemPromptPosition, restoredEntries)
        engine.setTemperature(character.creativity)
        for (message in messages.drop(metadata.coveredMessageCount)) {
            if (message.content.isBlank()) continue
            if (message.isUser) engine.seedUserMessage(message.content) else engine.seedAssistantMessage(message.content)
        }
        true
    }.getOrDefault(false)

    private fun applyHeader() {
        headerName.text = character.name.ifBlank { "Unnamed" }
        val bitmap = character.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        characterAvatarBitmap = bitmap
        if (bitmap != null) {
            headerAvatar.setImageBitmap(bitmap)
            headerAvatar.visibility = View.VISIBLE
            headerAvatarLetter.visibility = View.GONE
            messageAdapter.characterAvatar = bitmap
        } else {
            val style = character.avatarStyle()
            headerAvatar.visibility = View.GONE
            headerAvatarLetter.visibility = View.VISIBLE
            headerAvatarLetter.text = style.letter
            headerAvatarLetter.setTextColor(getColor(style.foregroundColorRes))
            headerAvatarTile.setCircularAvatarBackground(style.backgroundColorRes)
            messageAdapter.characterAvatar = null
            messageAdapter.characterAvatarStyle = style
        }
    }

    /** Full-bleed, tap-to-dismiss preview of the character's photo, opened from the chat header. */
    private fun showFullscreenAvatar(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(getColor(R.color.black))
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(imageView)
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Rebuilds the model's actual memory by replaying the persisted transcript, instead of just
     * restoring the UI list. A brand-new character (no messages yet) gets its greeting seeded and
     * saved as the first message, so from then on this is the only path that ever runs.
     *
     * [useCachedHistory] (on by default) lets a replay skip re-seeding the entire, ever-growing raw
     * transcript from scratch: if a [ConversationStore.loadCompactedHistory] snapshot was saved by
     * an earlier replay, only that (bounded by the context window) plus whatever messages came
     * after it need to be seeded. Safe even right after regenerate/edit-last trim the [messages]
     * list, since the snapshot is only used if it covers no more than the current (possibly just
     * shortened) list - a snapshot that covers more than that falls back to a full replay instead
     * of risking a mismatch. Either way, a fresh snapshot of the model's now-current memory is
     * saved at the end, so the *next* replay can benefit from it too.
     */
    private suspend fun replayConversation(tickerLabel: String = "Loading conversation", useCachedHistory: Boolean = true) {
        // A live elapsed-time readout, same reasoning as the generation ticker in
        // handleUserInput(): setting up a fresh character (system prompt + greeting) is its own
        // separate step that can take a while on a slow/misbehaving model, and this makes a long
        // wait here visibly alive instead of indistinguishable from a hang, with a precise number
        // to report back.
        val startMs = SystemClock.elapsedRealtime()
        val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            statusTv.visibility = View.VISIBLE
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - startMs) / 1000
                statusTv.text = "$tickerLabel... ${elapsedS}s"
                delay(1000)
            }
        }
        try {
            if (!attemptFastContextRestore()) {
                engine.setSystemPrompt(character.toSystemPrompt())
                engine.setTemperature(character.creativity)

                if (messages.isEmpty() && character.greeting.isNotBlank()) {
                    engine.seedAssistantMessage(character.greeting)
                    withContext(Dispatchers.Main) {
                        messages.add(Message(UUID.randomUUID().toString(), character.greeting, false))
                        messageAdapter.notifyItemInserted(messages.size - 1)
                    }
                    persist()
                } else {
                    val snapshot = if (useCachedHistory) ConversationStore.loadCompactedHistory(this, character.id) else null
                    val alreadyCoveredCount = if (snapshot != null && snapshot.second in 1..messages.size) {
                        for (entry in snapshot.first) {
                            when (entry.role) {
                                "user" -> engine.seedUserMessage(entry.content)
                                "assistant" -> engine.seedAssistantMessage(entry.content)
                                else -> engine.seedSystemNote(entry.content)
                            }
                        }
                        snapshot.second
                    } else 0

                    // Blank entries can't happen going forward (persist() filters them out), but
                    // skip them defensively anyway so an already-saved conversation from before
                    // that fix isn't stuck forever: seed*Message reject blank text.
                    for (message in messages.drop(alreadyCoveredCount)) {
                        if (message.content.isBlank()) continue
                        if (message.isUser) engine.seedUserMessage(message.content) else engine.seedAssistantMessage(message.content)
                    }
                }
            }

            // Snapshot the model's now-current compacted memory (rolling-summary notes plus
            // whatever raw turns are still live) so the *next* replay can resume from here instead
            // of always re-seeding the entire transcript from scratch again.
            runCatching {
                ConversationStore.saveCompactedHistory(this, character.id, engine.compactedHistory(), messages.size)
            }.onFailure { Log.w(TAG, "Failed to snapshot compacted history", it) }

            withContext(Dispatchers.Main) {
                statusTv.visibility = View.GONE
                isReady = true
                userInputEt.hint = "Type a message..."
                userInputEt.isEnabled = true
                sendFab.isEnabled = true
                toolbar.menu.findItem(R.id.action_edit_character)?.isEnabled = true
                toolbar.menu.findItem(R.id.action_clear_conversation)?.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare conversation", e)
            val detail = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
            withContext(Dispatchers.Main) {
                statusTv.visibility = View.VISIBLE
                statusTv.text = "Error loading the conversation."
                showErrorDetailsDialog(detail)
            }
        } finally {
            tickerJob.cancel()
        }
    }

    /**
     * A fleeting Toast isn't enough to actually report a native/JNI error back - there's no PC or
     * adb access to pull it from logcat otherwise. Show it as selectable text instead so it can be
     * copied and sent along with a bug report.
     */
    private fun showErrorDetailsDialog(detail: String, title: String = "Error loading the conversation") {
        val textView = TextView(this).apply {
            text = detail
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Long-press to select and copy the text below so it can be reported:")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun persist() {
        // Drop blank messages (e.g. the empty assistant placeholder added right before generation
        // starts) so a persisted transcript is never left with an unreplayable empty entry if the
        // app closes mid-reply.
        ConversationStore.save(this, character, messages.filter { it.content.isNotBlank() })
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
            return
        }
        userInputEt.text = null
        sendMessage(userMsg)
    }

    /**
     * Adds a user message and generates the reply to it. Shared by normal sending and by
     * [regenerateLastReply]/[startEditingLastUserMessage], which both remove the tail of the
     * conversation, resync the model's context to match, and then call this again.
     */
    private fun sendMessage(userMsg: String) {
        userInputEt.isEnabled = false
        sendFab.isEnabled = false

        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        messageAdapter.notifyItemInserted(messages.size - 1)
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false, isThinking = true))
        messageAdapter.notifyItemInserted(messages.size - 1)
        persist()

        // A live elapsed-time readout while waiting, so a long wait is visibly "still working"
        // rather than indistinguishable silence from something actually stuck - and gives a
        // precise number to report back instead of an estimate like "about 7 minutes".
        var stoppedByUser = false
        val generationStartMs = SystemClock.elapsedRealtime()
        val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            statusTv.visibility = View.VISIBLE
            stopGenerationButton.visibility = View.VISIBLE
            stopGenerationButton.isEnabled = true
            stopGenerationButton.setOnClickListener {
                engine.cancelGeneration()
                stoppedByUser = true
                stopGenerationButton.isEnabled = false
            }
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - generationStartMs) / 1000
                statusTv.text = "Thinking... ${elapsedS}s"
                delay(1000)
            }
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Reveal the reply only once generation is fully done, instead of as it's being
                // written, replacing the thinking indicator with the complete text.
                engine.sendUserPrompt(userMsg).collect { token -> lastAssistantMsg.append(token) }
                tickerJob.cancel()
                withContext(Dispatchers.Main) {
                    statusTv.visibility = View.GONE
                    stopGenerationButton.visibility = View.GONE
                    val messageCount = messages.size
                    check(messageCount > 0 && !messages[messageCount - 1].isUser)

                    val finalText = lastAssistantMsg.toString()
                    if (finalText.isBlank() && stoppedByUser) {
                        // Stopped before a single token came out - drop the placeholder instead
                        // of leaving (and persisting) an empty bubble.
                        messages.removeAt(messageCount - 1)
                        messageAdapter.notifyItemRemoved(messageCount - 1)
                    } else {
                        messages.removeAt(messageCount - 1).copy(
                            content = finalText,
                            isThinking = false
                        ).let { messages.add(it) }
                        messageAdapter.notifyItemChanged(messages.size - 1)
                    }
                    userInputEt.isEnabled = true
                    sendFab.isEnabled = true
                }
                persist()
            } catch (e: CancellationException) {
                tickerJob.cancel()
                throw e
            } catch (e: Exception) {
                // A native failure or other error during generation used to leave the thinking
                // placeholder stuck (or, since it's blank, silently turn into an empty reply
                // bubble once collection finished with nothing collected) with no explanation.
                // Drop it and say what happened instead, the same way replayConversation() does.
                tickerJob.cancel()
                Log.e(TAG, "Failed to generate a reply", e)
                val detail = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
                withContext(Dispatchers.Main) {
                    statusTv.text = "Error generating a reply."
                    stopGenerationButton.visibility = View.GONE
                    val messageCount = messages.size
                    if (messageCount > 0 && messages[messageCount - 1].isThinking) {
                        messages.removeAt(messageCount - 1)
                        messageAdapter.notifyItemRemoved(messageCount - 1)
                    }
                    userInputEt.isEnabled = true
                    sendFab.isEnabled = true
                    showErrorDetailsDialog(detail, title = "Error generating a reply")
                }
                persist()
            }
        }
    }

    /**
     * Drops the last exchange and asks the model for a fresh reply to the same user message -
     * the model's context has no "undo" of its own, so this resyncs it by replaying everything
     * up to (but not including) that pair, then resending the same user message.
     */
    private fun regenerateLastReply() {
        if (!isReady || generationJob?.isActive == true) return
        val lastIndex = messages.lastIndex
        if (lastIndex < 0) return
        val lastMessage = messages[lastIndex]
        if (lastMessage.isUser || lastMessage.isThinking) return
        val userIndex = lastIndex - 1
        if (userIndex < 0 || !messages[userIndex].isUser) return
        val userText = messages[userIndex].content

        messages.removeAt(lastIndex)
        messages.removeAt(userIndex)
        messageAdapter.notifyItemRangeRemoved(userIndex, 2)
        persist()
        resyncThenRun { sendMessage(userText) }
    }

    /**
     * Removes the last user message (and the reply it got, if any) and puts its text back in the
     * input box to edit, resyncing the model's context to match so the edited version replaces it
     * cleanly instead of the model seeing both.
     */
    private fun startEditingLastUserMessage() {
        if (!isReady || generationJob?.isActive == true) return
        val lastIndex = messages.lastIndex
        if (lastIndex < 0) return

        val (userIndex, removeCount) = when {
            messages[lastIndex].isUser -> lastIndex to 1
            lastIndex - 1 >= 0 && messages[lastIndex - 1].isUser && !messages[lastIndex].isThinking -> (lastIndex - 1) to 2
            else -> return
        }
        val userText = messages[userIndex].content

        repeat(removeCount) { messages.removeAt(messages.lastIndex) }
        messageAdapter.notifyItemRangeRemoved(userIndex, removeCount)
        persist()
        resyncThenRun {
            userInputEt.setText(userText)
            userInputEt.setSelection(userText.length)
            userInputEt.requestFocus()
        }
    }

    /** Resets and replays the (already-trimmed) [messages] list, then runs [onReady] on the main thread. */
    private fun resyncThenRun(onReady: () -> Unit) {
        isReady = false
        userInputEt.isEnabled = false
        sendFab.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            replayConversation(tickerLabel = "Preparing")
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    private fun confirmClearConversation() {
        AlertDialog.Builder(this)
            .setTitle("Clear conversation?")
            .setMessage("This deletes the chat history with ${character.name.ifBlank { "this character" }}. The character itself is kept.")
            .setPositiveButton("Clear") { _, _ -> clearConversation() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearConversation() {
        generationJob?.cancel()
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        persist()

        isReady = false
        userInputEt.isEnabled = false
        sendFab.isEnabled = false
        toolbar.menu.findItem(R.id.action_edit_character)?.isEnabled = false
        toolbar.menu.findItem(R.id.action_clear_conversation)?.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) { replayConversation() }
    }

    private val editCharacter = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(CharacterSetupActivity.EXTRA_CHARACTER_JSON) ?: return@registerForActivityResult
        character = Character.fromJson(JSONObject(json))
        applyHeader()
        clearConversation()
    }

    companion object {
        private val TAG = ChatActivity::class.java.simpleName
        const val EXTRA_CHARACTER_ID = "character_id"
    }
}
