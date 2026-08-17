package com.charchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusTv: TextView
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

        toolbar = findViewById(R.id.toolbar)
        statusTv = findViewById(R.id.status_tv)
        emptyStateTv = findViewById(R.id.empty_state_tv)
        charactersRv = findViewById(R.id.characters_rv)
        charactersRv.layoutManager = GridLayoutManager(this, 2)
        mainFab = findViewById(R.id.main_fab)

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
                withContext(Dispatchers.Main) { onModelReady() }
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

    private fun resumeOrPickModel() {
        val existingModel = ensureModelsDirectory().listFiles { f -> f.extension == "gguf" }
            ?.maxByOrNull { it.lastModified() }

        if (existingModel != null) {
            statusTv.text = "Loading ${existingModel.name}..."
            lifecycleScope.launch(Dispatchers.IO) {
                engine.loadModel(existingModel.path)
                withContext(Dispatchers.Main) { onModelReady() }
            }
        } else {
            statusTv.text = "Choose a .gguf model to get started."
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
                    withContext(Dispatchers.Main) { statusTv.text = "Loading the model..." }
                    engine.loadModel(modelFile.path)
                    withContext(Dispatchers.Main) { onModelReady() }
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

    private fun onModelReady() {
        isModelReady = true
        statusTv.visibility = View.GONE
        mainFab.setImageResource(R.drawable.ic_add_24)
        mainFab.isEnabled = true
        refreshCharacterList()
    }

    private fun refreshCharacterList() {
        val characters = ConversationStore.listCharacters(this)
        emptyStateTv.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
        charactersRv.visibility = if (characters.isEmpty()) View.GONE else View.VISIBLE
        charactersRv.adapter = CharacterCardAdapter(
            characters,
            onClick = { character -> openChat(character) },
            onLongClick = { character -> confirmDelete(character) }
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
