package com.charchat.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persists characters, groups, and their conversations to disk - one JSON file per character or
 * group, in separate directories, so nothing is lost when the app is closed and reopened, and
 * groups never get parsed as (garbage) characters or vice versa.
 */
object ConversationStore {
    private const val CHARACTERS_DIRECTORY = "characters"
    private const val GROUPS_DIRECTORY = "groups"

    private fun directory(context: Context, name: String) =
        File(context.filesDir, name).also { if (!it.exists()) it.mkdirs() }

    private fun charactersDirectory(context: Context) = directory(context, CHARACTERS_DIRECTORY)
    private fun groupsDirectory(context: Context) = directory(context, GROUPS_DIRECTORY)

    private fun fileFor(context: Context, id: String) = File(charactersDirectory(context), "$id.json")
    private fun groupFileFor(context: Context, id: String) = File(groupsDirectory(context), "$id.json")

    /** Character metadata only (for the gallery list), without loading full message history. */
    fun listCharacters(context: Context): List<Character> =
        charactersDirectory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { Character.fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            ?.sortedByDescending { fileFor(context, it.id).lastModified() }
            ?: emptyList()

    /** Group metadata only (for the gallery list), without loading full message history. */
    fun listGroups(context: Context): List<Group> =
        groupsDirectory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { Group.fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            ?.sortedByDescending { groupFileFor(context, it.id).lastModified() }
            ?: emptyList()

    /** Every character and group, interleaved by most-recently-touched, for the gallery grid. */
    fun listGalleryItems(context: Context): List<GalleryItem> {
        val characters = charactersDirectory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { Character.fromJson(JSONObject(file.readText())) }.getOrNull()
                    ?.let { GalleryItem.CharacterItem(it, file.lastModified()) }
            }
            ?: emptyList()

        val characterById = characters.associate { it.character.id to it.character }
        val groups = groupsDirectory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { Group.fromJson(JSONObject(file.readText())) }.getOrNull()
                    ?.let { group ->
                        val members = group.characterIds.mapNotNull { characterById[it] }
                        if (members.size < MIN_GROUP_SIZE) null // a member was deleted; not enough left for a group
                        else GalleryItem.GroupItem(group, members, file.lastModified())
                    }
            }
            ?: emptyList()

        return (characters + groups).sortedByDescending { it.sortKey }
    }

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

    fun loadGroupMessages(context: Context, id: String): List<Message> {
        val file = groupFileFor(context, id)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText())
            Message.listFromJson(json.optJSONArray("messages") ?: return emptyList())
        }.getOrDefault(emptyList())
    }

    fun saveGroup(context: Context, group: Group, messages: List<Message>) {
        val json = group.toJson().apply {
            put("messages", Message.listToJson(messages))
        }
        groupFileFor(context, group.id).writeText(json.toString())
    }

    fun deleteGroup(context: Context, id: String) {
        groupFileFor(context, id).delete()
    }
}
