package com.charchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class CharacterSetupActivity : AppCompatActivity() {

    private lateinit var avatarIv: ImageView
    private lateinit var nameEt: EditText
    private lateinit var physicalEt: EditText
    private lateinit var personalityEt: EditText
    private lateinit var scenarioEt: EditText
    private lateinit var userPersonaEt: EditText
    private lateinit var greetingEt: EditText
    private lateinit var creativitySlider: Slider
    private lateinit var creativityLabel: TextView
    private lateinit var creativityDescription: TextView
    private lateinit var startButton: MaterialButton

    private var avatarPath: String? = null
    private var editingId: String? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { copyAvatar(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_character_setup)
        findViewById<View>(R.id.setup_root).applySystemBarInsetsAsPadding()

        avatarIv = findViewById(R.id.avatar_image)
        nameEt = findViewById(R.id.character_name)
        physicalEt = findViewById(R.id.character_physical)
        personalityEt = findViewById(R.id.character_personality)
        scenarioEt = findViewById(R.id.character_scenario)
        userPersonaEt = findViewById(R.id.character_user_persona)
        greetingEt = findViewById(R.id.character_greeting)
        creativitySlider = findViewById(R.id.creativity_slider)
        creativityLabel = findViewById(R.id.creativity_label)
        creativityDescription = findViewById(R.id.creativity_description)
        startButton = findViewById(R.id.start_chat_button)

        creativitySlider.value = DEFAULT_CREATIVITY
        updateCreativityText(DEFAULT_CREATIVITY)
        creativitySlider.addOnChangeListener { _, value, _ -> updateCreativityText(value) }

        intent.getStringExtra(EXTRA_EDIT_CHARACTER_JSON)?.let { json ->
            prefill(Character.fromJson(JSONObject(json)))
        }

        avatarIv.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        startButton.setOnClickListener { submit() }
    }

    private fun updateCreativityText(value: Float) {
        val level = creativityLevelFor(value)
        creativityLabel.text = level.label
        creativityDescription.text = level.description
    }

    private fun prefill(character: Character) {
        editingId = character.id
        nameEt.setText(character.name)
        physicalEt.setText(character.physicalDescription)
        personalityEt.setText(character.personality)
        scenarioEt.setText(character.scenario)
        userPersonaEt.setText(character.userPersona)
        greetingEt.setText(character.greeting)
        creativitySlider.value = character.creativity
        updateCreativityText(character.creativity)
        avatarPath = character.avatarPath
        avatarPath?.let { path ->
            File(path).takeIf { it.exists() }?.let {
                avatarIv.setImageBitmap(BitmapFactory.decodeFile(it.path))
            }
        }
    }

    private fun copyAvatar(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)

            if (copied) {
                avatarPath = file.path
                withContext(Dispatchers.Main) {
                    avatarIv.setImageBitmap(BitmapFactory.decodeFile(file.path))
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CharacterSetupActivity, "Couldn't load the image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun submit() {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Give your character a name", Toast.LENGTH_SHORT).show()
            return
        }

        val character = editingId?.let { id ->
            Character(
                id = id,
                name = name,
                avatarPath = avatarPath,
                physicalDescription = physicalEt.text.toString().trim(),
                personality = personalityEt.text.toString().trim(),
                scenario = scenarioEt.text.toString().trim(),
                userPersona = userPersonaEt.text.toString().trim(),
                greeting = greetingEt.text.toString().trim(),
                creativity = creativitySlider.value
            )
        } ?: Character(
            name = name,
            avatarPath = avatarPath,
            physicalDescription = physicalEt.text.toString().trim(),
            personality = personalityEt.text.toString().trim(),
            scenario = scenarioEt.text.toString().trim(),
            userPersona = userPersonaEt.text.toString().trim(),
            greeting = greetingEt.text.toString().trim(),
            creativity = creativitySlider.value
        )

        setResult(RESULT_OK, Intent().putExtra(EXTRA_CHARACTER_JSON, character.toJson().toString()))
        finish()
    }

    companion object {
        const val EXTRA_CHARACTER_JSON = "character_json"
        const val EXTRA_EDIT_CHARACTER_JSON = "edit_character_json"
    }
}
