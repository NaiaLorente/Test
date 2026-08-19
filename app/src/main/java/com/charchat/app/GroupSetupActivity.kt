package com.charchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

/**
 * Picks [MIN_GROUP_SIZE]-[MAX_GROUP_SIZE] existing characters to start a group chat: one shared
 * scene where every character (and the user) sees everything everyone else says, instead of each
 * running its own independent conversation.
 */
class GroupSetupActivity : AppCompatActivity() {

    private lateinit var titleTv: TextView
    private lateinit var selectionCountTv: TextView
    private lateinit var charactersRv: RecyclerView
    private lateinit var nameEt: TextInputEditText
    private lateinit var scenarioEt: TextInputEditText
    private lateinit var creativitySlider: Slider
    private lateinit var creativityLabel: TextView
    private lateinit var creativityDescription: TextView
    private lateinit var startButton: MaterialButton

    private val allCharacters = mutableListOf<Character>()
    private val selectedIds = mutableSetOf<String>()
    private lateinit var adapter: SelectableCharacterAdapter
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_group_setup)
        findViewById<View>(R.id.group_setup_root).applySystemBarInsetsAsPadding()

        titleTv = findViewById(R.id.group_setup_title)
        selectionCountTv = findViewById(R.id.group_selection_count)
        charactersRv = findViewById(R.id.group_characters_rv)
        nameEt = findViewById(R.id.group_name_input)
        scenarioEt = findViewById(R.id.group_scenario_input)
        creativitySlider = findViewById(R.id.group_creativity_slider)
        creativityLabel = findViewById(R.id.group_creativity_label)
        creativityDescription = findViewById(R.id.group_creativity_description)
        startButton = findViewById(R.id.group_start_button)

        creativitySlider.value = DEFAULT_CREATIVITY
        updateCreativityText(DEFAULT_CREATIVITY)
        creativitySlider.addOnChangeListener { _, value, _ -> updateCreativityText(value) }

        allCharacters.addAll(ConversationStore.listCharacters(this))

        intent.getStringExtra(EXTRA_EDIT_GROUP_JSON)?.let { json ->
            prefill(Group.fromJson(JSONObject(json)))
        }

        adapter = SelectableCharacterAdapter(allCharacters, selectedIds) { toggle(it) }
        charactersRv.layoutManager = LinearLayoutManager(this)
        charactersRv.adapter = adapter
        updateSelectionCount()

        startButton.setOnClickListener { submit() }
    }

    private fun prefill(group: Group) {
        editingId = group.id
        titleTv.text = "Edit group chat"
        startButton.text = "Save changes"
        nameEt.setText(group.name)
        scenarioEt.setText(group.scenario)
        creativitySlider.value = group.creativity
        updateCreativityText(group.creativity)
        selectedIds.addAll(group.characterIds)
    }

    private fun toggle(character: Character) {
        if (selectedIds.contains(character.id)) {
            selectedIds.remove(character.id)
        } else if (selectedIds.size < MAX_GROUP_SIZE) {
            selectedIds.add(character.id)
        } else {
            Toast.makeText(this, "You can pick up to $MAX_GROUP_SIZE characters", Toast.LENGTH_SHORT).show()
            return
        }
        adapter.notifyDataSetChanged()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        selectionCountTv.text = "${selectedIds.size} of $MAX_GROUP_SIZE selected (minimum $MIN_GROUP_SIZE)"
    }

    private fun updateCreativityText(value: Float) {
        val level = creativityLevelFor(value)
        creativityLabel.text = level.label
        creativityDescription.text = level.description
    }

    private fun submit() {
        if (selectedIds.size < MIN_GROUP_SIZE) {
            Toast.makeText(this, "Pick at least $MIN_GROUP_SIZE characters", Toast.LENGTH_SHORT).show()
            return
        }

        // Preserve the order characters are shown in, not JSON set-iteration order.
        val orderedIds = allCharacters.map { it.id }.filter { it in selectedIds }
        val group = editingId?.let { id ->
            Group(
                id = id,
                name = nameEt.text.toString().trim(),
                characterIds = orderedIds,
                scenario = scenarioEt.text.toString().trim(),
                creativity = creativitySlider.value
            )
        } ?: Group(
            name = nameEt.text.toString().trim(),
            characterIds = orderedIds,
            scenario = scenarioEt.text.toString().trim(),
            creativity = creativitySlider.value
        )

        setResult(RESULT_OK, Intent().putExtra(EXTRA_GROUP_JSON, group.toJson().toString()))
        finish()
    }

    companion object {
        const val EXTRA_GROUP_JSON = "group_json"
        const val EXTRA_EDIT_GROUP_JSON = "edit_group_json"
    }
}

private class SelectableCharacterAdapter(
    private val characters: List<Character>,
    private val selectedIds: Set<String>,
    private val onToggle: (Character) -> Unit
) : RecyclerView.Adapter<SelectableCharacterAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarTile: View = view.findViewById(R.id.select_avatar_tile)
        val avatarPhoto: ShapeableImageView = view.findViewById(R.id.select_avatar_photo)
        val avatarLetter: TextView = view.findViewById(R.id.select_avatar_letter)
        val name: TextView = view.findViewById(R.id.select_name)
        val checkbox: CheckBox = view.findViewById(R.id.select_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_character_selectable, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val character = characters[position]
        holder.name.text = character.name.ifBlank { "Unnamed" }
        holder.checkbox.isChecked = character.id in selectedIds

        val bitmap = character.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        if (bitmap != null) {
            holder.avatarPhoto.setImageBitmap(bitmap)
            holder.avatarPhoto.visibility = View.VISIBLE
            holder.avatarLetter.visibility = View.GONE
        } else {
            val style = character.avatarStyle()
            holder.avatarPhoto.visibility = View.GONE
            holder.avatarLetter.visibility = View.VISIBLE
            holder.avatarLetter.text = style.letter
            holder.avatarLetter.setTextColor(holder.itemView.context.getColor(style.foregroundColorRes))
            holder.avatarTile.setBackgroundColor(holder.itemView.context.getColor(style.backgroundColorRes))
        }

        holder.itemView.setOnClickListener { onToggle(character) }
    }

    override fun getItemCount(): Int = characters.size
}
