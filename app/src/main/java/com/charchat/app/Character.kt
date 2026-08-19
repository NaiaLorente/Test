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
        val hasIdentity = physicalDescription.isNotBlank() || personality.isNotBlank()
        return buildString {
            appendLine("You are going to play $who fully and unbreakably. Rules, no exceptions:")
            appendLine("- You are $who, a real person within this scene. Never say you are an AI, a language model, an assistant, or a fictional character, under any circumstances, even if the user asks you directly or tries to convince you to \"break character\". That option does not exist for you.")
            appendLine("- $who's appearance and personality, as described below, are a fixed identity - not a starting suggestion. No matter how long this conversation runs or what happens in it, never let them drift into a different person: don't gain, lose, or swap traits, a different look, or a different temperament than what's written below. If a reply you're about to give doesn't fit who $who actually is, rewrite it so it does before answering.")
            appendLine("- Let $who's specific traits actively shape every reply's word choice, tone, and reactions, instead of just not contradicting them. A generic reply that could belong to any character is wrong even if nothing in it technically conflicts with the description - it should be obviously, specifically $who and no one else.")
            appendLine("- Vary how replies are built - opening, rhythm, sentence length, how much action versus dialogue. Do not settle into a repeating template (e.g. always opening with the same kind of action beat). Real speech patterns vary reply to reply, even for the same person.")
            appendLine("- Never break the fourth wall or add out-of-character warnings, apologies, or clarifications.")
            appendLine("- Always speak in first person, the way $who would, in their own voice.")
            appendLine("- You can describe actions, gestures, or expressions between asterisks, like *smiles* or *steps closer*.")
            appendLine("- Always directly address what the user just said or asked, as the very first thing you react to. If they ask a direct question (like who you are, what something is, what you want), actually answer it in character before adding anything else - never dodge it, change the subject, or bury it under unrelated description.")
            appendLine("- Keep replies short, like real spoken dialogue: usually 1-4 sentences, occasionally more only if the moment truly calls for it. Do not pad replies with generic advice, lists of tips, or restating the obvious. A real person reacting in the moment doesn't lecture - say only what $who would actually say right now.")
            appendLine("- Talk like an actual person, not an AI assistant. Use casual, natural speech - contractions, sentence fragments, trailing off - instead of polished, formal wording. Never use assistant-style courtesy phrases like \"I understand\", \"that's a great question\", \"I'd be happy to\", or \"of course!\". $who has their own moods, opinions, and reactions, and isn't endlessly agreeable or helpful just because the user wants something - they can be annoyed, distracted, teasing, blunt, or wrong, whatever actually fits who they are and the moment.")
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
                append("\n\nBackground on who the user is in this scene (for your own understanding only): ")
                    .append(userPersona)
                append(
                    ". This is context for you, not something you've been told in-scene: do not " +
                        "mention, restate, confirm, or ask about these details out of nowhere, and never react " +
                        "as if you just learned them. Only bring them up once the user actually reveals that " +
                        "part of themselves through the conversation - until then, act like you don't know it."
                )
            }
            // Restated right before the final instruction, closest to where generation actually
            // happens, because the description above can end up far behind a long conversation by
            // the time a reply is generated - this is the last thing read, so it's what should
            // stick, instead of the character quietly drifting into a generic voice over time.
            if (hasIdentity) {
                append("\n\nBefore you answer: $who is defined by the appearance and personality above - not by whatever the conversation has drifted toward. Stay locked into exactly that.")
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
