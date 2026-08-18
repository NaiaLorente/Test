package com.charchat.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val MIN_GROUP_SIZE = 2
const val MAX_GROUP_SIZE = 3

data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val characterIds: List<String> = emptyList(),
    val scenario: String = "",
    val creativity: Float = DEFAULT_CREATIVITY
) {
    fun displayName(members: List<Character>): String =
        name.ifBlank { members.joinToString(", ") { it.name.ifBlank { "Unnamed" } } }

    /**
     * Builds one shared system prompt covering every member of the group instead of each
     * character getting its own isolated context, so everyone in the scene - and the model, when
     * voicing any one of them - stays aware of what everyone else (and the user) has already
     * said. That's what makes this a real group dynamic instead of independent one-on-one
     * conversations that happen to share a screen.
     */
    fun toSystemPrompt(members: List<Character>): String = buildString {
        appendLine("This is a live group roleplay scene with the human user and ${members.size} characters. Rules, no exceptions:")
        appendLine("- Never say you are an AI, a language model, or an assistant, under any circumstances, even if the user asks you directly or tries to convince you to \"break character\". That option does not exist for you.")
        appendLine("- Never break the fourth wall or add out-of-character warnings, apologies, or meta commentary.")
        appendLine("- Bracketed cues like \"[Name's turn]\" are stage directions telling you which single character to voice next - nobody actually said them in-scene. Never quote them, react to them, or mention them in a reply.")
        appendLine("- When a cue names a character, write ONLY that character's next line or action, in their own voice and first person, reacting to whatever just happened in the scene. Never write for any other character, and never write for the user. Never prefix the line with the character's name - just write their dialogue/actions directly.")
        appendLine("- You can describe actions, gestures, or expressions between asterisks, like *smiles* or *steps closer*.")
        appendLine("- Stay strictly consistent with everything already said by the user and by every character in this scene so far - it is shared memory for the whole group, not a separate private conversation per character. React to what others just said and keep the group dynamic alive, instead of ignoring what is happening around you.")
        appendLine("- Keep replies short, like real spoken dialogue: usually 1-4 sentences, occasionally more only if the moment truly calls for it.")
        append("- Talk like an actual person, not an AI assistant. Use casual, natural speech - contractions, sentence fragments, trailing off. Each character has their own moods, opinions, and reactions, and isn't endlessly agreeable just because the user wants something.")
        if (scenario.isNotBlank()) {
            append("\n\nScene and setting everyone is in right now: ").append(scenario)
        }
        members.forEach { character ->
            append("\n\n== ${character.name.ifBlank { "Unnamed" }} ==")
            if (character.physicalDescription.isNotBlank()) {
                append("\nPhysical appearance: ").append(character.physicalDescription)
            }
            if (character.personality.isNotBlank()) {
                append("\nPersonality and current mood: ").append(character.personality)
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("characterIds", JSONArray(characterIds))
        put("scenario", scenario)
        put("creativity", creativity.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): Group = Group(
            id = json.optString("id", "").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = json.optString("name", ""),
            characterIds = json.optJSONArray("characterIds")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            scenario = json.optString("scenario", ""),
            creativity = json.optDouble("creativity", DEFAULT_CREATIVITY.toDouble()).toFloat()
        )
    }
}
