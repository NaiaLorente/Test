package com.charchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Lets the user see every downloaded GGUF model, switch which one is loaded, delete ones no
 * longer needed, or add another - without the only cleanup path being clearing all app data
 * (which would also wipe every character and conversation).
 */
class ModelManagerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var modelsRv: RecyclerView
    private lateinit var addFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_model_manager)
        findViewById<View>(R.id.model_manager_root).applySystemBarInsetsAsPadding()

        toolbar = findViewById(R.id.model_manager_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        statusTv = findViewById(R.id.model_manager_status)
        emptyTv = findViewById(R.id.model_manager_empty_tv)
        modelsRv = findViewById(R.id.models_rv)
        modelsRv.layoutManager = LinearLayoutManager(this)
        addFab = findViewById(R.id.model_manager_add_fab)
        addFab.setOnClickListener { if (!isBusy) getContent.launch(arrayOf("*/*")) }

        refreshList()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
            }
            // Reflects whether generation is running elsewhere (e.g. a chat screen backgrounded
            // mid-reply) in real time, since switching or unloading would otherwise fail outright.
            engine.state.collect { state ->
                withContext(Dispatchers.Main) { updateBusyBanner(state) }
            }
        }
    }

    private fun updateBusyBanner(state: InferenceEngine.State) {
        if (isBusy) return // our own ticker text owns the status line right now
        if (state is InferenceEngine.State.ModelReady) {
            statusTv.visibility = View.GONE
        } else {
            statusTv.visibility = View.VISIBLE
            statusTv.text = "A reply is being generated elsewhere - switching or deleting the active model is disabled until it finishes."
        }
    }

    private fun canSwitchModelsNow(): Boolean =
        ::engine.isInitialized && !isBusy && engine.state.value is InferenceEngine.State.ModelReady

    private fun refreshList() {
        val models = ModelStorage.listModels(this)
        val activeName = ModelStorage.activeModelName(this)
        emptyTv.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        modelsRv.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        modelsRv.adapter = ModelAdapter(
            models,
            activeName,
            onSwitch = { file -> confirmSwitch(file) },
            onDelete = { file -> confirmDeleteModel(file) }
        )
    }

    private fun confirmSwitch(file: File) {
        if (!canSwitchModelsNow()) {
            Toast.makeText(this, "Wait for the current reply to finish first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Switch to ${file.name}?")
            .setMessage("The currently loaded model will be unloaded first.")
            .setPositiveButton("Switch") { _, _ -> switchTo(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchTo(file: File) {
        isBusy = true
        addFab.isEnabled = false
        modelsRv.adapter = null

        val startMs = SystemClock.elapsedRealtime()
        val tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            statusTv.visibility = View.VISIBLE
            while (isActive) {
                val elapsedS = (SystemClock.elapsedRealtime() - startMs) / 1000
                statusTv.text = "Switching to ${file.name}... ${elapsedS}s"
                delay(1000)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.cleanUp()
                engine.loadModel(file.path)
                ModelStorage.setActiveModel(this@ModelManagerActivity, file.name)
                withContext(Dispatchers.Main) {
                    statusTv.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch models", e)
                val detail = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
                withContext(Dispatchers.Main) {
                    statusTv.text = "Error switching models."
                    showErrorDetailsDialog(detail, title = "Error switching models")
                }
            } finally {
                tickerJob.cancel()
                withContext(Dispatchers.Main) {
                    isBusy = false
                    addFab.isEnabled = true
                    refreshList()
                }
            }
        }
    }

    private fun confirmDeleteModel(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}?")
            .setMessage("This frees up ${ModelStorage.formatSize(file.length())} of storage. You'll need to add it again to use it.")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { addModel(it) } }

    private fun addModel(uri: Uri) {
        isBusy = true
        addFab.isEnabled = false
        statusTv.visibility = View.VISIBLE
        statusTv.text = "Reading the model..."

        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Parsing GGUF metadata...")
            val modelFile = contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input -> ensureModelFile(modelName, input) }
            }

            if (modelFile == null) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "Couldn't read that file as a GGUF model."
                    isBusy = false
                    addFab.isEnabled = true
                }
                return@launch
            }

            if (engine.state.value is InferenceEngine.State.ModelReady) {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    switchTo(modelFile)
                }
            } else {
                withContext(Dispatchers.Main) {
                    statusTv.text = "Added ${modelFile.name}. Switch to it once the current reply finishes."
                    isBusy = false
                    addFab.isEnabled = true
                    refreshList()
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ModelStorage.modelsDirectory(this@ModelManagerActivity), modelName).also { file ->
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { statusTv.text = "Copying the model..." }
                    FileOutputStream(file).use { input.copyTo(it) }
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    private fun showErrorDetailsDialog(detail: String, title: String) {
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

    override fun finish() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTIVE_MODEL_NAME, ModelStorage.activeModelName(this)))
        super.finish()
    }

    companion object {
        private val TAG = ModelManagerActivity::class.java.simpleName
        const val EXTRA_ACTIVE_MODEL_NAME = "active_model_name"
    }
}

private class ModelAdapter(
    private val models: List<File>,
    private val activeName: String?,
    private val onSwitch: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.model_row_name)
        val size: TextView = view.findViewById(R.id.model_row_size)
        val activeBadge: View = view.findViewById(R.id.model_row_active_badge)
        val delete: View = view.findViewById(R.id.model_row_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = models[position]
        val isActive = file.name == activeName
        holder.name.text = file.name
        holder.size.text = ModelStorage.formatSize(file.length())
        holder.activeBadge.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.delete.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.delete.setOnClickListener { onDelete(file) }
        holder.itemView.setOnClickListener { if (!isActive) onSwitch(file) }
    }

    override fun getItemCount(): Int = models.size
}
