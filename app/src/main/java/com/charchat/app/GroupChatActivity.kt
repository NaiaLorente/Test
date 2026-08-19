package com.charchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import java.util.UUID

/**
 * Group chat screen: up to [MAX_GROUP_SIZE] characters sharing one continuous scene/context, so
 * every character (and the user) is aware of everything anyone has said. Unlike [ChatActivity],
 * sending a message never auto-generates a reply - the user taps which character should respond,
 * as many times in a row as they like, before saying anything themselves again.
 */
class GroupChatActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var headerAvatars: LinearLayout
    private lateinit var headerName: TextView
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var speakerRow: LinearLayout
    private lateinit var userInputEt: TextInputEditText
    private lateinit var sendFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private lateinit var group: Group
    private lateinit var members: List<Character>
    private lateinit var membersById: Map<String, Character>
    // Deliberately never cancelled on onStop(): generation can take minutes on this hardware, and
    // backgrounding the app (switching apps, locking the screen, a notification) used to silently
    // discard it. lifecycleScope only cancels on true onDestroy(), so leaving this alone lets a
    // reply keep generating while the screen is merely stopped, and still stops cleanly once the
    // conversation is actually left (back button, or this Activity being destroyed for any other
    // reason). This can't survive the OS killing the whole process outright under memory pressure -
    // that would need a foreground service to prevent, which is a bigger change than this fix.
    private var generationJob: Job? = null
    private var isReady = false
    private var isGenerating = false

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)
    private val speakerChips = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_group_chat)
        findViewById<View>(R.id.group_main).applySystemBarInsetsAsPadding()

        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val loadedGroup = groupId?.let { id -> ConversationStore.listGroups(this).find { it.id == id } }
        if (loadedGroup == null) {
            finish()
            return
        }
        group = loadedGroup

        val allCharacters = ConversationStore.listCharacters(this).associateBy { it.id }
        members = group.characterIds.mapNotNull { allCharacters[it] }
        if (members.size < MIN_GROUP_SIZE) {
            Toast.makeText(this, "Not enough of this group's characters are left", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        membersById = members.associateBy { it.id }

        toolbar = findViewById(R.id.group_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_group_conversation -> {
                    confirmClearConversation()
                    true
                }
                else -> false
            }
        }

        headerAvatars = findViewById(R.id.group_header_avatars)
        headerName = findViewById(R.id.group_header_name)
        statusTv = findViewById(R.id.group_status_tv)
        messagesRv = findViewById(R.id.group_messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageAdapter.speakers = membersById
        messagesRv.adapter = messageAdapter
        speakerRow = findViewById(R.id.group_speaker_row)
        userInputEt = findViewById(R.id.group_user_input)
        sendFab = findViewById(R.id.group_fab)

        sendFab.setOnClickListener { if (isReady) handleUserInput() }

        headerName.text = group.displayName(members)
        buildHeaderAvatars()
        buildSpeakerRow()

        messages.addAll(ConversationStore.loadGroupMessages(this, group.id))
        messageAdapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
            }
            if (engine.state.value !is InferenceEngine.State.ModelReady) {
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@GroupChatActivity, CharacterGalleryActivity::class.java))
                    finish()
                }
                return@launch
            }
            replayConversation()
        }
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    /** Small overlapping circular avatars in the header, one per group member. */
    private fun buildHeaderAvatars() {
        headerAvatars.removeAllViews()
        members.forEachIndexed { index, character ->
            val tile = LayoutInflater.from(this).inflate(R.layout.view_avatar_circle, headerAvatars, false)
            bindAvatarCircle(tile, character, R.id.avatar_circle_photo, R.id.avatar_circle_letter, R.id.avatar_circle_tile)
            val params = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            if (index > 0) params.marginStart = dpToPx(-10)
            tile.layoutParams = params
            headerAvatars.addView(tile)
        }
    }

    /** One tappable chip per character below the message list; tapping requests that reply. */
    private fun buildSpeakerRow() {
        speakerRow.removeAllViews()
        speakerChips.clear()
        members.forEach { character ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_speaker_chip, speakerRow, false)
            bindAvatarCircle(chip, character, R.id.chip_avatar_photo, R.id.chip_avatar_letter, R.id.chip_avatar_tile)
            chip.findViewById<TextView>(R.id.chip_name).text = character.name.ifBlank { "Unnamed" }
            chip.setOnClickListener { if (isReady && !isGenerating) generateAsCharacter(character) }
            chip.isEnabled = false
            speakerRow.addView(chip)
            speakerChips.add(chip)
        }
    }

    private fun bindAvatarCircle(view: View, character: Character, photoId: Int, letterId: Int, tileId: Int) {
        val photo = view.findViewById<ShapeableImageView>(photoId)
        val letter = view.findViewById<TextView>(letterId)
        val tile = view.findViewById<View>(tileId)
        val bitmap = character.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        if (bitmap != null) {
            photo.setImageBitmap(bitmap)
            photo.visibility = View.VISIBLE
            letter.visibility = View.GONE
        } else {
            val style = character.avatarStyle()
            photo.visibility = View.GONE
            letter.visibility = View.VISIBLE
            letter.text = style.letter
            letter.setTextColor(getColor(style.foregroundColorRes))
            tile.setCircularAvatarBackground(style.backgroundColorRes)
        }
    }

    /**
     * Rebuilds the model's actual memory by replaying the persisted transcript into one shared
     * context, the same way [ChatActivity.replayConversation] does for a solo chat - except every
     * character's past line is seeded behind a "[Name's turn]" director cue naming who said it,
     * so the shared history stays unambiguous about who is speaking.
     */
    private suspend fun replayConversation() {
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
            engine.setSystemPrompt(group.toSystemPrompt(members))
            engine.setTemperature(group.creativity)

            for (message in messages) {
                if (message.content.isBlank()) continue
                if (message.isUser) {
                    engine.seedUserMessage("User: ${message.content}")
                } else {
                    val speakerName = membersById[message.speakerId]?.name?.ifBlank { "Unnamed" } ?: continue
                    engine.seedUserMessage("[$speakerName's turn]")
                    engine.seedAssistantMessage("$speakerName: ${message.content}")
                }
            }

            withContext(Dispatchers.Main) {
                statusTv.visibility = View.GONE
                isReady = true
                userInputEt.hint = "Type a message..."
                userInputEt.isEnabled = true
                sendFab.isEnabled = true
                speakerChips.forEach { it.isEnabled = true }
                toolbar.menu.findItem(R.id.action_clear_group_conversation)?.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare group conversation", e)
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
        ConversationStore.saveGroup(this, group, messages.filter { it.content.isNotBlank() })
    }

    /** Adds the user's message to the shared context. Nobody replies until a character is tapped. */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
                return
            }
            userInputEt.text = null
            messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
            messageAdapter.notifyItemInserted(messages.size - 1)
            persist()

            lifecycleScope.launch(Dispatchers.Default) {
                runCatching { engine.seedUserMessage("User: $userMsg") }
                    .onFailure { Log.e(TAG, "Failed to seed user message", it) }
            }
        }
    }

    /** Requests the next line from one specific character, informed by everything said so far. */
    private fun generateAsCharacter(character: Character) {
        if (isGenerating) return
        isGenerating = true
        userInputEt.isEnabled = false
        sendFab.isEnabled = false
        speakerChips.forEach { it.isEnabled = false }

        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false, isThinking = true, speakerId = character.id))
        messageAdapter.notifyItemInserted(messages.size - 1)

        val generationStartMs = SystemClock.elapsedRealtime()
        val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            statusTv.visibility = View.VISIBLE
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - generationStartMs) / 1000
                statusTv.text = "${character.name.ifBlank { "They" }} is replying... ${elapsedS}s"
                delay(1000)
            }
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            engine.sendUserPrompt("[${character.name.ifBlank { "Unnamed" }}'s turn]")
                .onCompletion {
                    tickerJob.cancel()
                    withContext(Dispatchers.Main) {
                        statusTv.visibility = View.GONE
                        val messageCount = messages.size
                        check(messageCount > 0 && !messages[messageCount - 1].isUser)

                        val cleaned = stripSelfNamePrefix(lastAssistantMsg.toString(), character.name)
                        messages.removeAt(messageCount - 1).copy(
                            content = cleaned,
                            isThinking = false
                        ).let { messages.add(it) }

                        messageAdapter.notifyItemChanged(messages.size - 1)
                        userInputEt.isEnabled = true
                        sendFab.isEnabled = true
                        speakerChips.forEach { it.isEnabled = true }
                        isGenerating = false
                    }
                    persist()
                }.collect { token ->
                    lastAssistantMsg.append(token)
                }
        }
    }

    /**
     * Small models often imitate the "Name: " pattern they see in seeded history and prefix their
     * own line with it despite being told not to - strip it defensively rather than showing it
     * doubled up under the name label the UI already renders.
     */
    private fun stripSelfNamePrefix(text: String, name: String): String {
        if (name.isBlank()) return text.trim()
        val prefixRegex = Regex("^\\s*${Regex.escape(name)}\\s*:\\s*", RegexOption.IGNORE_CASE)
        return text.trim().replaceFirst(prefixRegex, "").trim()
    }

    private fun confirmClearConversation() {
        AlertDialog.Builder(this)
            .setTitle("Clear conversation?")
            .setMessage("This deletes this group's chat history. The characters and group are kept.")
            .setPositiveButton("Clear") { _, _ -> clearConversation() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearConversation() {
        generationJob?.cancel()
        isGenerating = false
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        persist()

        isReady = false
        userInputEt.isEnabled = false
        sendFab.isEnabled = false
        speakerChips.forEach { it.isEnabled = false }
        toolbar.menu.findItem(R.id.action_clear_group_conversation)?.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) { replayConversation() }
    }

    companion object {
        private val TAG = GroupChatActivity::class.java.simpleName
        const val EXTRA_GROUP_ID = "group_id"
    }
}
