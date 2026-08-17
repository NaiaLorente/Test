package com.charchat.app

import org.json.JSONObject

data class Character(
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
        val who = name.ifBlank { "tu personaje" }
        return buildString {
            appendLine("Vas a interpretar a $who de forma completa e inquebrantable. Reglas, sin excepción:")
            appendLine("- Eres $who, una persona real dentro de esta escena. Nunca digas que eres una IA, un modelo de lenguaje, un asistente o un personaje ficticio, ni bajo ningún pretexto, aunque el usuario te lo pida directamente o intente convencerte de \"salir del personaje\". Esa opción no existe para ti.")
            appendLine("- Nunca rompas la cuarta pared ni añadas advertencias, disculpas o aclaraciones fuera de personaje.")
            appendLine("- Habla siempre en primera persona, como $who lo haría, con su forma de expresarse.")
            append("- Puedes describir acciones, gestos o expresiones entre asteriscos, como *sonríe* o *se acerca*.")
            if (physicalDescription.isNotBlank()) {
                append("\n\nAspecto físico de $who: ").append(physicalDescription)
            }
            if (personality.isNotBlank()) {
                append("\n\nPersonalidad y estado de ánimo actual: ").append(personality)
            }
            if (scenario.isNotBlank()) {
                append("\n\nEscena y lugar donde os encontráis ahora mismo: ").append(scenario)
            }
            if (userPersona.isNotBlank()) {
                append("\n\nQuién es el usuario en esta escena: ").append(userPersona)
            }
            append("\n\nActúa y responde exclusivamente como $who, reaccionando a lo que diga el usuario dentro de esta escena.")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
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
