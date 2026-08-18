package com.charchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import com.arm.aichat.gguf.GgufMetadata
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
        loadedModelLabel = findViewById(R.id.loaded_model_label)
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
                val modelName = ensureModelsDirectory().listFiles { f -> f.extension == "gguf" }
                    ?.maxByOrNull { it.lastModified() }?.name
                withContext(Dispatchers.Main) { onModelReady(modelName) }
            } else {
                withContext(Dispatchers.Main) { resumeOrPickModel() }
            }
        }

        mainFab.setOnClickListener {
            if (isModelReady) {
                characterSetup.launch(Intent(this, CharacterSetupActivity::class.java))
            } else {
                getContent.launch(arrayOf("*/*"))
            }
        }
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
        val existingModel = ensureModelsDirectory().listFiles { f -> f.extension == "gguf" }
            ?.maxByOrNull { it.lastModified() }

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
            File(ensureModelsDirectory(), modelName).also { file ->
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
        val characters = ConversationStore.listCharacters(this)
        characterCountTv.text = if (characters.size == 1) "1 ready to chat" else "${characters.size} ready to chat"
        emptyStateTv.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
        charactersRv.visibility = if (characters.isEmpty()) View.GONE else View.VISIBLE
        charactersRv.adapter = CharacterCardAdapter(
            characters,
            onClick = { character -> openChat(character) },
            onDeleteClick = { character -> confirmDelete(character) }
        )
    }

    private fun openChat(character: Character) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHARACTER_ID, character.id))
    }

    private fun confirmDelete(character: Character) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${character.name.ifBlank { "this character" }}?")
            .setMessage("This will permanently delete the character and its conversation.")
            .setPositiveButton("Delete") { _, _ ->
                ConversationStore.delete(this, character.id)
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

    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    companion object {
        private val TAG = CharacterGalleryActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
