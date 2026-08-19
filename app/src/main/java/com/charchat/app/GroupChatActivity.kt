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
    private lateinit var stopGenerationButton: TextView
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
    private val messageAdapter = MessageAdapter(
        messages,
        onRegenerateLast = { regenerateLastReply() },
        onEditLastUser = { startEditingLastUserMessage() }
    )
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
                R.id.action_edit_group -> {
                    if (isGenerating) {
                        Toast.makeText(this, "Wait for the current reply to finish first", Toast.LENGTH_SHORT).show()
                    } else {
                        editGroup.launch(
                            Intent(this, GroupSetupActivity::class.java)
                                .putExtra(GroupSetupActivity.EXTRA_EDIT_GROUP_JSON, group.toJson().toString())
                        )
                    }
                    true
                }
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
        stopGenerationButton = findViewById(R.id.group_stop_generation_button)
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
            replayConversation(useCachedHistory = true)
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
        if (::group.isInitialized && ::engine.isInitialized && isReady) {
            val coveredMessageCount = messages.size
            lifecycleScope.launch(Dispatchers.Default) {
                runCatching { saveContextStateSnapshot(coveredMessageCount) }
                    .onFailure { Log.w(TAG, "Failed to save context state", it) }
            }
        }
    }

    private suspend fun saveContextStateSnapshot(coveredMessageCount: Int) {
        val modelName = ModelStorage.activeModelName(this) ?: return
        engine.saveContextState(ConversationStore.groupContextStateFile(this, group.id).path)
        ConversationStore.saveGroupContextStateMetadata(
            context = this,
            groupId = group.id,
            modelName = modelName,
            systemPromptHash = group.toSystemPrompt(members).hashCode().toString(),
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
     * match the currently active model, the group's scenario or member list changed since
     * (different system prompt hash), or the file is missing/corrupt - so the caller can fall back
     * to a normal replay exactly as if this function didn't exist.
     */
    private suspend fun attemptFastContextRestore(): Boolean = runCatching {
        val metadata = ConversationStore.loadGroupContextStateMetadata(this, group.id) ?: return@runCatching false
        if (metadata.coveredMessageCount !in 1..messages.size) return@runCatching false
        if (metadata.modelName != ModelStorage.activeModelName(this)) return@runCatching false
        val systemPrompt = group.toSystemPrompt(members)
        if (metadata.systemPromptHash != systemPrompt.hashCode().toString()) return@runCatching false

        val stateFile = ConversationStore.groupContextStateFile(this, group.id)
        if (!engine.loadContextState(stateFile.path)) return@runCatching false

        engine.restoreContext(systemPrompt, metadata.systemPromptPosition, metadata.entries)
        engine.setTemperature(group.creativity)
        for (message in messages.drop(metadata.coveredMessageCount)) {
            if (message.content.isBlank()) continue
            if (message.isUser) {
                engine.seedUserMessage("User: ${message.content}")
            } else {
                val speakerName = membersById[message.speakerId]?.name?.ifBlank { "Unnamed" } ?: continue
                engine.seedUserMessage("[$speakerName's turn]")
                engine.seedAssistantMessage("$speakerName: ${message.content}")
            }
        }
        true
    }.getOrDefault(false)

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
            val displayName = character.name.ifBlank { "Unnamed" }
            chip.findViewById<TextView>(R.id.chip_name).text = displayName
            chip.contentDescription = "Get a reply from $displayName"
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
     *
     * See [ChatActivity.replayConversation] for what [useCachedHistory] (on by default) does: skips
     * re-seeding the entire raw transcript if an earlier replay saved a bounded snapshot - safe
     * even right after regenerate/edit-last/edit-group trim or change the [messages] list, since a
     * snapshot covering more than the current list is never used, falling back to a full replay.
     */
    private suspend fun replayConversation(tickerLabel: String = "Loading conversation", useCachedHistory: Boolean = true) {
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
                engine.setSystemPrompt(group.toSystemPrompt(members))
                engine.setTemperature(group.creativity)

                val snapshot = if (useCachedHistory) ConversationStore.loadCompactedGroupHistory(this, group.id) else null
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

                for (message in messages.drop(alreadyCoveredCount)) {
                    if (message.content.isBlank()) continue
                    if (message.isUser) {
                        engine.seedUserMessage("User: ${message.content}")
                    } else {
                        val speakerName = membersById[message.speakerId]?.name?.ifBlank { "Unnamed" } ?: continue
                        engine.seedUserMessage("[$speakerName's turn]")
                        engine.seedAssistantMessage("$speakerName: ${message.content}")
                    }
                }
            }

            // Snapshot the model's now-current compacted memory so the *next* replay can resume
            // from here instead of always re-seeding the entire transcript from scratch again.
            runCatching {
                ConversationStore.saveCompactedGroupHistory(this, group.id, engine.compactedHistory(), messages.size)
            }.onFailure { Log.w(TAG, "Failed to snapshot compacted history", it) }

            withContext(Dispatchers.Main) {
                statusTv.visibility = View.GONE
                isReady = true
                userInputEt.hint = "Type a message..."
                userInputEt.isEnabled = true
                sendFab.isEnabled = true
                speakerChips.forEach { it.isEnabled = true }
                toolbar.menu.findItem(R.id.action_edit_group)?.isEnabled = true
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
            stopGenerationButton.visibility = View.VISIBLE
            stopGenerationButton.isEnabled = true
            stopGenerationButton.setOnClickListener {
                engine.cancelGeneration()
                stopGenerationButton.isEnabled = false
            }
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - generationStartMs) / 1000
                statusTv.text = "${character.name.ifBlank { "They" }} is replying... ${elapsedS}s"
                delay(1000)
            }
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine.sendUserPrompt("[${character.name.ifBlank { "Unnamed" }}'s turn]")
                    .collect { token -> lastAssistantMsg.append(token) }
                tickerJob.cancel()
                withContext(Dispatchers.Main) {
                    statusTv.visibility = View.GONE
                    stopGenerationButton.visibility = View.GONE
                    val messageCount = messages.size
                    check(messageCount > 0 && !messages[messageCount - 1].isUser)

                    val cleaned = trimVoiceBleed(
                        stripSelfNamePrefix(lastAssistantMsg.toString(), character.name),
                        character
                    )
                    if (cleaned.isBlank()) {
                        // Stopped before a single token came out, or the entire reply turned out
                        // to be voice bleed into someone else's line - drop the placeholder
                        // instead of leaving (and persisting) an empty bubble.
                        messages.removeAt(messageCount - 1)
                        messageAdapter.notifyItemRemoved(messageCount - 1)
                    } else {
                        messages.removeAt(messageCount - 1).copy(
                            content = cleaned,
                            isThinking = false
                        ).let { messages.add(it) }
                        messageAdapter.notifyItemChanged(messages.size - 1)
                    }
                    userInputEt.isEnabled = true
                    sendFab.isEnabled = true
                    speakerChips.forEach { it.isEnabled = true }
                    isGenerating = false
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
                Log.e(TAG, "Failed to generate ${character.name}'s reply", e)
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
                    speakerChips.forEach { it.isEnabled = true }
                    isGenerating = false
                    showErrorDetailsDialog(detail, title = "Error generating a reply")
                }
                persist()
            }
        }
    }

    /**
     * Drops the last reply and asks the same character for a fresh one - mirrors
     * [ChatActivity.regenerateLastReply], resyncing the model's context to just before that line
     * and then re-issuing the same "[Name's turn]" cue.
     */
    private fun regenerateLastReply() {
        if (!isReady || isGenerating) return
        val lastIndex = messages.lastIndex
        if (lastIndex < 0) return
        val lastMessage = messages[lastIndex]
        if (lastMessage.isUser || lastMessage.isThinking) return
        val character = lastMessage.speakerId?.let { membersById[it] } ?: return

        messages.removeAt(lastIndex)
        messageAdapter.notifyItemRemoved(lastIndex)
        persist()
        resyncThenRun { generateAsCharacter(character) }
    }

    /**
     * Removes the last user message (and the reply it got, if any) and puts its text back in the
     * input box to edit - mirrors [ChatActivity.startEditingLastUserMessage].
     */
    private fun startEditingLastUserMessage() {
        if (!isReady || isGenerating) return
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

    /**
     * Resets and replays the (already-trimmed) [messages] list, then runs [onReady] on the main
     * thread. [useCachedHistory] defaults to true (see [replayConversation]), but is turned off by
     * the group-edit flow: a saved snapshot can contain cues/lines from a member who was just
     * removed from the group, which the raw-[messages] replay path knows to filter out (via
     * [membersById]) but a cached snapshot's already-seeded content does not.
     */
    private fun resyncThenRun(useCachedHistory: Boolean = true, onReady: () -> Unit) {
        isReady = false
        userInputEt.isEnabled = false
        sendFab.isEnabled = false
        speakerChips.forEach { it.isEnabled = false }
        lifecycleScope.launch(Dispatchers.Default) {
            replayConversation(tickerLabel = "Preparing", useCachedHistory = useCachedHistory)
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    private val editGroup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(GroupSetupActivity.EXTRA_GROUP_JSON) ?: return@registerForActivityResult
        group = Group.fromJson(JSONObject(json))

        val allCharacters = ConversationStore.listCharacters(this).associateBy { it.id }
        members = group.characterIds.mapNotNull { allCharacters[it] }
        if (members.size < MIN_GROUP_SIZE) {
            Toast.makeText(this, "Not enough of this group's characters are left", Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        membersById = members.associateBy { it.id }

        headerName.text = group.displayName(members)
        buildHeaderAvatars()
        buildSpeakerRow()
        messageAdapter.speakers = membersById
        persist()

        // The system prompt depends on the group's scenario and member list, either of which may
        // have just changed - resync the model's actual context to match instead of leaving it
        // built from the pre-edit version. useCachedHistory=false: a saved snapshot could still
        // include a just-removed member's lines that a full raw replay knows to filter out.
        resyncThenRun(useCachedHistory = false) {}
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

    /**
     * A turn is only supposed to contain the one character's own line - nothing stops a small
     * model from imitating the seeded "Name: " pattern again mid-reply and drifting into writing
     * another character's line, or the user's, in the same generation. Cut the reply at the first
     * such spillover instead of showing content this character never should have said.
     */
    private fun trimVoiceBleed(text: String, speaker: Character): String {
        val otherNames = (members.map { it.name.ifBlank { "Unnamed" } } + "User")
            .filterNot { it.equals(speaker.name.ifBlank { "Unnamed" }, ignoreCase = true) }
            .distinct()
        if (otherNames.isEmpty()) return text
        val namePattern = otherNames.joinToString("|") { Regex.escape(it) }
        val bleedRegex = Regex("(?:^|\\n)\\s*(?:$namePattern)\\s*:", RegexOption.IGNORE_CASE)
        val match = bleedRegex.find(text) ?: return text
        return text.substring(0, match.range.first).trim()
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
        toolbar.menu.findItem(R.id.action_edit_group)?.isEnabled = false
        toolbar.menu.findItem(R.id.action_clear_group_conversation)?.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) { replayConversation() }
    }

    companion object {
        private val TAG = GroupChatActivity::class.java.simpleName
        const val EXTRA_GROUP_ID = "group_id"
    }
}
