package com.charchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var startButton: MaterialButton

    private var avatarPath: String? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { copyAvatar(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_character_setup)

        avatarIv = findViewById(R.id.avatar_image)
        nameEt = findViewById(R.id.character_name)
        physicalEt = findViewById(R.id.character_physical)
        personalityEt = findViewById(R.id.character_personality)
        scenarioEt = findViewById(R.id.character_scenario)
        userPersonaEt = findViewById(R.id.character_user_persona)
        greetingEt = findViewById(R.id.character_greeting)
        startButton = findViewById(R.id.start_chat_button)

        CharacterStore.load(this)?.let { prefill(it) }

        avatarIv.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        startButton.setOnClickListener { submit() }
    }

    private fun prefill(character: Character) {
        nameEt.setText(character.name)
        physicalEt.setText(character.physicalDescription)
        personalityEt.setText(character.personality)
        scenarioEt.setText(character.scenario)
        userPersonaEt.setText(character.userPersona)
        greetingEt.setText(character.greeting)
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

        val character = Character(
            name = name,
            avatarPath = avatarPath,
            physicalDescription = physicalEt.text.toString().trim(),
            personality = personalityEt.text.toString().trim(),
            scenario = scenarioEt.text.toString().trim(),
            userPersona = userPersonaEt.text.toString().trim(),
            greeting = greetingEt.text.toString().trim()
        )
        CharacterStore.save(this, character)

        setResult(RESULT_OK, Intent().putExtra(EXTRA_CHARACTER_JSON, character.toJson().toString()))
        finish()
    }

    companion object {
        const val EXTRA_CHARACTER_JSON = "character_json"
    }
}
