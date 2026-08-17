package com.charchat.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: TextInputEditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var benchButton: ImageButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        toolbar = findViewById(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_edit_character) {
                openCharacterSetup()
                true
            } else {
                false
            }
        }

        statusTv = findViewById(R.id.status_tv)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        benchButton = findViewById(R.id.bench_button)

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            // Native library loading happens asynchronously inside the engine; wait for it to
            // settle before touching loadModel(), which requires the Initialized state.
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
            }
            withContext(Dispatchers.Main) { resumeOrPickModel() }
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                handleUserInput()
            } else {
                getContent.launch(arrayOf("*/*"))
            }
        }

        benchButton.setOnClickListener { handleBenchmarkRequest() }
    }

    /**
     * If a model was already imported on a previous run, load it straight away instead of
     * making the user pick the file again every time they open the app.
     */
    private fun resumeOrPickModel() {
        val existingModel = ensureModelsDirectory().listFiles { f -> f.extension == "gguf" }
            ?.maxByOrNull { it.lastModified() }

        if (existingModel != null) {
            statusTv.text = "Cargando ${existingModel.name}..."
            lifecycleScope.launch(Dispatchers.IO) {
                loadModel(existingModel.name, existingModel)
                withContext(Dispatchers.Main) { onModelReady() }
            }
        } else {
            statusTv.text = "Elige un modelo .gguf para empezar."
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    private fun handleSelectedModel(uri: Uri) {
        userActionFab.isEnabled = false
        statusTv.text = "Leyendo el modelo..."

        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                Log.i(TAG, "GGUF parsed: \n$metadata")
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModel(modelName, modelFile)
                    withContext(Dispatchers.Main) { onModelReady() }
                }
            }
        }
    }

    private suspend fun onModelReady() {
        userActionFab.isEnabled = false
        openCharacterSetup()
    }

    private fun openCharacterSetup() {
        characterSetup.launch(Intent(this, CharacterSetupActivity::class.java))
    }

    private val characterSetup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(CharacterSetupActivity.EXTRA_CHARACTER_JSON) ?: return@registerForActivityResult
        applyCharacter(Character.fromJson(JSONObject(json)))
    }

    private fun applyCharacter(newCharacter: Character) {
        generationJob?.cancel()
        toolbar.title = newCharacter.name

        val avatarBitmap: Bitmap? = newCharacter.avatarPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
        messageAdapter.characterAvatar = avatarBitmap

        messages.clear()
        messageAdapter.notifyDataSetChanged()

        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        statusTv.visibility = View.VISIBLE
        statusTv.text = "Metiéndose en el papel de ${newCharacter.name}..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine.setSystemPrompt(newCharacter.toSystemPrompt())
                if (newCharacter.greeting.isNotBlank()) {
                    engine.seedAssistantMessage(newCharacter.greeting)
                }
                withContext(Dispatchers.Main) {
                    if (newCharacter.greeting.isNotBlank()) {
                        messages.add(Message(UUID.randomUUID().toString(), newCharacter.greeting, false))
                        messageAdapter.notifyItemInserted(messages.size - 1)
                    }
                    statusTv.visibility = View.GONE
                    isModelReady = true
                    userInputEt.hint = "Escribe un mensaje..."
                    userInputEt.isEnabled = true
                    benchButton.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    toolbar.menu.findItem(R.id.action_edit_character)?.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply character", e)
                withContext(Dispatchers.Main) {
                    statusTv.visibility = View.VISIBLE
                    statusTv.text = "Error al preparar el personaje."
                    Toast.makeText(this@MainActivity, "Error al preparar el personaje: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) { statusTv.text = "Copiando el modelo..." }
                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) { statusTv.text = "Cargando el modelo..." }
            engine.loadModel(modelFile.path)
        }

    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Escribe algo primero", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                messageAdapter.notifyItemInserted(messages.size - 1)
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))
                messageAdapter.notifyItemInserted(messages.size - 1)

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    private fun handleBenchmarkRequest() {
        benchButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) { runBenchmark() }
            .invokeOnCompletion {
                lifecycleScope.launch(Dispatchers.Main) { benchButton.isEnabled = true }
            }
    }

    private suspend fun runBenchmark() =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking")
            val result = engine.bench(
                pp = BENCH_PROMPT_PROCESSING_TOKENS,
                tg = BENCH_TOKEN_GENERATION_TOKENS,
                pl = BENCH_SEQUENCE,
                nr = BENCH_REPETITION
            )
            withContext(Dispatchers.Main) {
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                messageAdapter.notifyItemInserted(messages.size - 1)
            }
        }

    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
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
