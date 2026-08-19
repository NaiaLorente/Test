package com.charchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.NATIVE_CRASH_LOG_FILE_NAME
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Launcher screen: gates on loading a GGUF model once, then shows every saved character as a
 * card. Tapping a card resumes that character's persisted conversation in [ChatActivity]; the
 * "+" button creates a new one.
 */
class CharacterGalleryActivity : AppCompatActivity() {

    private lateinit var brandHeader: View
    private lateinit var galleryHeader: View
    private lateinit var loadedModelRow: View
    private lateinit var loadedModelLabel: TextView
    private lateinit var statusTv: TextView
    private lateinit var characterCountTv: TextView
    private lateinit var emptyStateTv: TextView
    private lateinit var charactersRv: RecyclerView
    private lateinit var mainFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private var isModelReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_character_gallery)
        findViewById<View>(R.id.gallery_root).applySystemBarInsetsAsPadding()

        brandHeader = findViewById(R.id.brand_header)
        galleryHeader = findViewById(R.id.gallery_header)
        loadedModelRow = findViewById(R.id.loaded_model_row)
        loadedModelLabel = findViewById(R.id.loaded_model_label)
        loadedModelRow.setOnClickListener { manageModels.launch(Intent(this, ModelManagerActivity::class.java)) }
        statusTv = findViewById(R.id.status_tv)
        characterCountTv = findViewById(R.id.character_count_tv)
        emptyStateTv = findViewById(R.id.empty_state_tv)
        charactersRv = findViewById(R.id.characters_rv)
        charactersRv.layoutManager = GridLayoutManager(this, 2)
        mainFab = findViewById(R.id.main_fab)

        showPreviousCrashIfAny()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            // Native library loading happens asynchronously inside the engine; wait for it to
            // settle before touching loadModel(), which requires the Initialized state.
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
            }
            if (engine.state.value is InferenceEngine.State.ModelReady) {
                // A model is already loaded in the shared singleton engine (e.g. this screen was
                // recreated after being backgrounded, or a chat screen bounced back here) -
                // calling loadModel() again would throw, since it requires the Initialized state.
                val modelName = ModelStorage.activeModelName(this@CharacterGalleryActivity)
                withContext(Dispatchers.Main) { onModelReady(modelName) }
            } else {
                withContext(Dispatchers.Main) { resumeOrPickModel() }
            }
        }

        mainFab.setOnClickListener {
            if (isModelReady) {
                showAddMenu()
            } else {
                getContent.launch(arrayOf("*/*"))
            }
        }
    }

    private val manageModels = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val activeName = result.data?.getStringExtra(ModelManagerActivity.EXTRA_ACTIVE_MODEL_NAME) ?: return@registerForActivityResult
        loadedModelLabel.text = activeName
        refreshCharacterList()
    }

    private fun showAddMenu() {
        val popup = PopupMenu(this, mainFab)
        popup.menuInflater.inflate(R.menu.fab_menu, popup.menu)
        popup.menu.findItem(R.id.action_new_group).isEnabled =
            ConversationStore.listCharacters(this).size >= MIN_GROUP_SIZE
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_character -> {
                    characterSetup.launch(Intent(this, CharacterSetupActivity::class.java))
                    true
                }
                R.id.action_new_group -> {
                    groupSetup.launch(Intent(this, GroupSetupActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        if (isModelReady) {
            refreshCharacterList()
        }
    }

    /**
     * A native crash (e.g. the app closing while "Loading conversation..." was showing) leaves no
     * catchable exception and can't be inspected via logcat without a PC. If the previous run left
     * a crash log behind, show it so the user can copy its contents and report back what actually
     * happened, instead of the crash being a silent dead end.
     */
    private fun showPreviousCrashIfAny() {
        val crashLog = File(filesDir, NATIVE_CRASH_LOG_FILE_NAME)
        if (!crashLog.exists() || crashLog.length() == 0L) {
            return
        }
        val content = runCatching { crashLog.readText() }.getOrDefault("(failed to read crash log)")
        val textView = TextView(this).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("The app closed unexpectedly last time")
            .setMessage("Details below - long-press to select and copy the text so it can be reported:")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Dismiss") { _, _ -> crashLog.delete() }
            .setCancelable(false)
            .show()
    }

    private fun resumeOrPickModel() {
        // Prefer whichever model was explicitly marked active (set on every successful load,
        // including an explicit switch from the model manager, which doesn't necessarily touch
        // file timestamps) - falling back to the most recently modified file for an install from
        // before that marker existed, or if it's somehow gone missing.
        val activeName = ModelStorage.activeModelName(this)
        val existingModel = activeName?.let { File(ModelStorage.modelsDirectory(this), it) }
            ?: ModelStorage.listModels(this).firstOrNull()

        if (existingModel != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                loadModelWithTicker(existingModel.name, existingModel.path)
                withContext(Dispatchers.Main) { onModelReady(existingModel.name) }
            }
        } else {
            statusTv.text = "Choose a .gguf model to get started."
        }
    }

    /**
     * Loading the model itself (reading the file, building its context/compute buffers) is a
     * separate, earlier step from setting up a conversation - and can be where a slow/misbehaving
     * model actually gets stuck, as happened with one Stheno-8B quant. A live "Loading X... Ys"
     * readout here gives a precise number to report back instead of an open-ended wait with no
     * way to tell "still working" from "hung".
     */
    private suspend fun loadModelWithTicker(modelName: String, modelPath: String) {
        val startMs = SystemClock.elapsedRealtime()
        val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - startMs) / 1000
                statusTv.text = "Loading $modelName... ${elapsedS}s"
                delay(1000)
            }
        }
        try {
            engine.loadModel(modelPath)
            ModelStorage.setActiveModel(this, modelName)
        } finally {
            tickerJob.cancel()
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(it) } }

    private fun handleSelectedModel(uri: Uri) {
        mainFab.isEnabled = false
        statusTv.text = "Reading the model..."

        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModelWithTicker(modelFile.name, modelFile.path)
                    withContext(Dispatchers.Main) { onModelReady(modelFile.name) }
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ModelStorage.modelsDirectory(this@CharacterGalleryActivity), modelName).also { file ->
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { statusTv.text = "Copying the model..." }
                    FileOutputStream(file).use { input.copyTo(it) }
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    private fun onModelReady(modelName: String? = null) {
        isModelReady = true
        modelName?.let { loadedModelLabel.text = it }
        brandHeader.visibility = View.GONE
        galleryHeader.visibility = View.VISIBLE
        mainFab.setImageResource(R.drawable.ic_add_24)
        mainFab.isEnabled = true
        refreshCharacterList()
    }

    private fun refreshCharacterList() {
        val items = ConversationStore.listGalleryItems(this)
        characterCountTv.text = if (items.size == 1) "1 ready to chat" else "${items.size} ready to chat"
        emptyStateTv.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        charactersRv.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        charactersRv.adapter = GalleryAdapter(
            items,
            onOpenCharacter = { character -> openChat(character) },
            onOpenGroup = { group -> openGroupChat(group) },
            onDeleteCharacter = { character -> confirmDelete(character) },
            onDeleteGroup = { group -> confirmDeleteGroup(group) }
        )
    }

    private fun openChat(character: Character) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHARACTER_ID, character.id))
    }

    private fun openGroupChat(group: Group) {
        startActivity(Intent(this, GroupChatActivity::class.java).putExtra(GroupChatActivity.EXTRA_GROUP_ID, group.id))
    }

    /**
     * A character can be part of one or more group chats - deleting them used to silently strand
     * those groups (or leave a dangling member reference in ones that survive), with nothing ever
     * telling the user or cleaning it up. This warns about exactly what will happen to each
     * affected group, and actually resolves it on confirm: a group that would drop below
     * [MIN_GROUP_SIZE] members is deleted along with the character, and one that still has enough
     * members left gets its membership list pruned instead of keeping a dead reference forever.
     */
    private fun confirmDelete(character: Character) {
        val affectedGroups = ConversationStore.groupsContaining(this, character.id)
        val allCharacters = ConversationStore.listCharacters(this).associateBy { it.id }
        fun groupLabel(group: Group) = group.displayName(group.characterIds.mapNotNull { allCharacters[it] })

        val orphanedGroups = affectedGroups.filter { it.characterIds.size - 1 < MIN_GROUP_SIZE }
        val shrinkingGroups = affectedGroups - orphanedGroups.toSet()

        val message = buildString {
            append("This will permanently delete the character and its conversation.")
            if (orphanedGroups.isNotEmpty()) {
                val names = orphanedGroups.joinToString(", ") { "\"${groupLabel(it)}\"" }
                val plural = orphanedGroups.size > 1
                append("\n\n${character.name.ifBlank { "This character" }} is the last thing keeping ")
                append(if (plural) "these group chats" else "the group chat $names")
                append(if (plural) " ($names) " else " ")
                append("above the $MIN_GROUP_SIZE-character minimum - ")
                append(if (plural) "they" else "it")
                append(" will be permanently deleted too, along with ")
                append(if (plural) "their conversations." else "its conversation.")
            }
            if (shrinkingGroups.isNotEmpty()) {
                val names = shrinkingGroups.joinToString(", ") { "\"${groupLabel(it)}\"" }
                append("\n\nThey'll also be removed from ")
                append(if (shrinkingGroups.size > 1) "these group chats: $names" else "the group chat $names")
                append(".")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Delete ${character.name.ifBlank { "this character" }}?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                ConversationStore.delete(this, character.id)
                orphanedGroups.forEach { ConversationStore.deleteGroup(this, it.id) }
                shrinkingGroups.forEach { group ->
                    val prunedGroup = group.copy(characterIds = group.characterIds - character.id)
                    ConversationStore.saveGroup(this, prunedGroup, ConversationStore.loadGroupMessages(this, group.id))
                }
                refreshCharacterList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteGroup(group: Group) {
        AlertDialog.Builder(this)
            .setTitle("Delete this group chat?")
            .setMessage("This will permanently delete the group and its conversation. The characters in it are kept.")
            .setPositiveButton("Delete") { _, _ ->
                ConversationStore.deleteGroup(this, group.id)
                refreshCharacterList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val characterSetup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(CharacterSetupActivity.EXTRA_CHARACTER_JSON) ?: return@registerForActivityResult
        val character = Character.fromJson(JSONObject(json))
        ConversationStore.save(this, character, emptyList())
        openChat(character)
    }

    private val groupSetup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(GroupSetupActivity.EXTRA_GROUP_JSON) ?: return@registerForActivityResult
        val group = Group.fromJson(JSONObject(json))
        ConversationStore.saveGroup(this, group, emptyList())
        openGroupChat(group)
    }

    companion object {
        private val TAG = CharacterGalleryActivity::class.java.simpleName
    }
}
