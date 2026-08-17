package com.charchat.app

import org.json.JSONObject
import java.util.UUID

data class Character(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val avatarPath: String? = null,
    val physicalDescription: String = "",
    val personality: String = "",
    val scenario: String = "",
    val userPersona: String = "",
    val greeting: String = ""
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
            append("- You can describe actions, gestures, or expressions between asterisks, like *smiles* or *steps closer*.")
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
            greeting = json.optString("greeting", "")
        )
    }
}
