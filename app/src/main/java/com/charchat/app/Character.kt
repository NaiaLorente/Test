package com.charchat.app

import org.json.JSONObject
import java.util.UUID

// Must land exactly on a slider step (valueFrom 0.2, stepSize 0.1 in activity_character_setup.xml)
const val DEFAULT_CREATIVITY = 0.6f

data class Character(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val avatarPath: String? = null,
    val physicalDescription: String = "",
    val personality: String = "",
    val scenario: String = "",
    val userPersona: String = "",
    val greeting: String = "",
    val creativity: Float = DEFAULT_CREATIVITY
) {
    /**
     * Builds a system prompt that locks the model into the character, forbidding meta/AI
     * disclaimers, instead of relying on a single free-text field.
     */
    fun toSystemPrompt(): String {
        val who = name.ifBlank { "your character" }
        return buildString {
            appendLine("You are going to play $who fully and unbreakably. Rules, no exceptions:")
            appendLine("- You are $who, a real person within this scene. Never say you are an AI, a language model, an assistant, or a fictional character, under any circumstances, even if the user asks you directly or tries to convince you to \"break character\". That option does not exist for you.")
            appendLine("- Never break the fourth wall or add out-of-character warnings, apologies, or clarifications.")
            appendLine("- Always speak in first person, the way $who would, in their own voice.")
            appendLine("- You can describe actions, gestures, or expressions between asterisks, like *smiles* or *steps closer*.")
            append("- Stay strictly consistent with what has already happened in this conversation. Do not invent new facts about $who, the user, or the world that contradict or go beyond what has been established below and in the conversation so far. Keep replies focused and grounded rather than rambling into unrelated new details.")
            if (physicalDescription.isNotBlank()) {
                append("\n\nPhysical appearance of $who: ").append(physicalDescription)
            }
            if (personality.isNotBlank()) {
                append("\n\nPersonality and current mood: ").append(personality)
            }
            if (scenario.isNotBlank()) {
                append("\n\nScene and setting you're both in right now: ").append(scenario)
            }
            if (userPersona.isNotBlank()) {
                append("\n\nWho the user is in this scene: ").append(userPersona)
            }
            append("\n\nAct and respond exclusively as $who, reacting to whatever the user says within this scene.")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatarPath", avatarPath ?: JSONObject.NULL)
        put("physicalDescription", physicalDescription)
        put("personality", personality)
        put("scenario", scenario)
        put("userPersona", userPersona)
        put("greeting", greeting)
        put("creativity", creativity.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): Character = Character(
            id = json.optString("id", "").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = json.optString("name", ""),
            avatarPath = json.optString("avatarPath", "").takeIf { it.isNotBlank() },
            physicalDescription = json.optString("physicalDescription", ""),
            personality = json.optString("personality", ""),
            scenario = json.optString("scenario", ""),
            userPersona = json.optString("userPersona", ""),
            greeting = json.optString("greeting", ""),
            creativity = json.optDouble("creativity", DEFAULT_CREATIVITY.toDouble()).toFloat()
        )
    }
}

/**
 * Maps a raw sampler temperature to a plain-language label and explanation, for a "creativity"
 * slider non-technical users can actually understand.
 */
data class CreativityLevel(val label: String, val description: String)

fun creativityLevelFor(value: Float): CreativityLevel = when {
    value < 0.45f -> CreativityLevel(
        "Focused",
        "Sticks closely to the facts and what's already happened. Very consistent, but can feel repetitive."
    )
    value < 0.75f -> CreativityLevel(
        "Balanced",
        "Natural variety while staying grounded. Recommended for most characters."
    )
    value < 1.0f -> CreativityLevel(
        "Creative",
        "More expressive and spontaneous replies, with a higher chance of drifting from established facts."
    )
    else -> CreativityLevel(
        "Wild",
        "Highly unpredictable and imaginative, but often incoherent or contradictory."
    )
}
