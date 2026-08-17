package com.charchat.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persists characters and their conversations to disk, one JSON file per character, so nothing
 * is lost when the app is closed and reopened. Multiple characters can be stored independently.
 */
object ConversationStore {
    private const val DIRECTORY = "characters"

    private fun directory(context: Context) =
        File(context.filesDir, DIRECTORY).also { if (!it.exists()) it.mkdirs() }

    private fun fileFor(context: Context, id: String) = File(directory(context), "$id.json")

    /** Character metadata only (for the gallery list), without loading full message history. */
    fun listCharacters(context: Context): List<Character> =
        directory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { Character.fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            ?.sortedByDescending { fileFor(context, it.id).lastModified() }
            ?: emptyList()

    fun loadMessages(context: Context, id: String): List<Message> {
        val file = fileFor(context, id)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText())
            Message.listFromJson(json.optJSONArray("messages") ?: return emptyList())
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, character: Character, messages: List<Message>) {
        val json = character.toJson().apply {
            put("messages", Message.listToJson(messages))
        }
        fileFor(context, character.id).writeText(json.toString())
    }

    fun delete(context: Context, id: String) {
        fileFor(context, id).delete()
    }
}
