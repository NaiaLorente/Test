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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
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
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: TextInputEditText
    private lateinit var sendFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private lateinit var character: Character
    private var generationJob: Job? = null
    private var isReady = false
    private var characterAvatarBitmap: Bitmap? = null

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

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
     */
    private suspend fun replayConversation() {
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
                statusTv.text = "Loading conversation... ${elapsedS}s"
                delay(1000)
            }
        }
        try {
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
                // Blank entries can't happen going forward (persist() filters them out), but
                // skip them defensively anyway so an already-saved conversation from before that
                // fix isn't stuck forever: seedUserMessage/seedAssistantMessage reject blank text.
                for (message in messages) {
                    if (message.content.isBlank()) continue
                    if (message.isUser) engine.seedUserMessage(message.content) else engine.seedAssistantMessage(message.content)
                }
            }

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
    private fun showErrorDetailsDialog(detail: String) {
        val textView = TextView(this).apply {
            text = detail
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Error loading the conversation")
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
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
                return
            }
            userInputEt.text = null
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
            val generationStartMs = SystemClock.elapsedRealtime()
            val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
                statusTv.visibility = View.VISIBLE
                while (isActive) {
                    val elapsedS = (SystemClock.elapsedRealtime() - generationStartMs) / 1000
                    statusTv.text = "Thinking... ${elapsedS}s"
                    delay(1000)
                }
            }

            generationJob = lifecycleScope.launch(Dispatchers.Default) {
                engine.sendUserPrompt(userMsg)
                    .onCompletion {
                        tickerJob.cancel()
                        // Reveal the reply only once generation is fully done, instead of as it's
                        // being written, replacing the thinking indicator with the complete text.
                        withContext(Dispatchers.Main) {
                            statusTv.visibility = View.GONE
                            val messageCount = messages.size
                            check(messageCount > 0 && !messages[messageCount - 1].isUser)

                            messages.removeAt(messageCount - 1).copy(
                                content = lastAssistantMsg.toString(),
                                isThinking = false
                            ).let { messages.add(it) }

                            messageAdapter.notifyItemChanged(messages.size - 1)
                            userInputEt.isEnabled = true
                            sendFab.isEnabled = true
                        }
                        persist()
                    }.collect { token ->
                        lastAssistantMsg.append(token)
                    }
            }
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

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private val TAG = ChatActivity::class.java.simpleName
        const val EXTRA_CHARACTER_ID = "character_id"
    }
}
