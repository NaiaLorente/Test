package com.charchat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One entry of the model's native chat history after the pinned system message: role + exact text. */
data class HistoryEntry(val role: String, val content: String)

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

    private const val COMPACTED_HISTORY_KEY = "compactedHistory"
    private const val COMPACTED_HISTORY_COUNT_KEY = "compactedHistoryCount"

    private fun readJsonFile(file: File): JSONObject? =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null

    /**
     * A plain [save]/[saveGroup] only knows about the character/group fields and the raw message
     * list - carry forward whatever compacted-history snapshot [saveCompactedHistory] previously
     * wrote for this file, so an ordinary message-list persist doesn't silently erase it.
     */
    private fun carryForwardCompactedHistory(target: JSONObject, existing: JSONObject?) {
        existing ?: return
        existing.optJSONArray(COMPACTED_HISTORY_KEY)?.let { target.put(COMPACTED_HISTORY_KEY, it) }
        if (existing.has(COMPACTED_HISTORY_COUNT_KEY)) {
            target.put(COMPACTED_HISTORY_COUNT_KEY, existing.optInt(COMPACTED_HISTORY_COUNT_KEY))
        }
    }

    private fun parseHistoryEntries(array: JSONArray): List<HistoryEntry> =
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            HistoryEntry(obj.optString("role"), obj.optString("content"))
        }

    /**
     * A previously saved [HistoryEntry] snapshot of the model's native chat history, paired with
     * how many of the full (persisted) message list's leading entries it already covers - or null
     * if none was ever saved. A cold-start replay can seed just this snapshot instead of the
     * entire transcript, then only seed whatever messages came after it.
     */
    private fun loadCompactedHistory(file: File): Pair<List<HistoryEntry>, Int>? {
        val json = readJsonFile(file) ?: return null
        val count = json.optInt(COMPACTED_HISTORY_COUNT_KEY, -1)
        val array = json.optJSONArray(COMPACTED_HISTORY_KEY) ?: return null
        if (count <= 0) return null
        return runCatching { parseHistoryEntries(array) to count }.getOrNull()
    }

    private fun saveCompactedHistory(file: File, historyJson: String, coveredCount: Int) {
        val json = readJsonFile(file) ?: return // character/group must already exist on disk
        runCatching {
            json.put(COMPACTED_HISTORY_KEY, JSONArray(historyJson))
            json.put(COMPACTED_HISTORY_COUNT_KEY, coveredCount)
            file.writeText(json.toString())
        }
    }

    fun loadCompactedHistory(context: Context, characterId: String) = loadCompactedHistory(fileFor(context, characterId))
    fun saveCompactedHistory(context: Context, characterId: String, historyJson: String, coveredCount: Int) =
        saveCompactedHistory(fileFor(context, characterId), historyJson, coveredCount)

    fun loadCompactedGroupHistory(context: Context, groupId: String) = loadCompactedHistory(groupFileFor(context, groupId))
    fun saveCompactedGroupHistory(context: Context, groupId: String, historyJson: String, coveredCount: Int) =
        saveCompactedHistory(groupFileFor(context, groupId), historyJson, coveredCount)

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
        val file = fileFor(context, character.id)
        val json = character.toJson().apply {
            put("messages", Message.listToJson(messages))
        }
        carryForwardCompactedHistory(json, readJsonFile(file))
        file.writeText(json.toString())
    }

    fun delete(context: Context, id: String) {
        fileFor(context, id).delete()
    }

    /** Every group this character is currently a member of, so deleting them can warn about it. */
    fun groupsContaining(context: Context, characterId: String): List<Group> =
        listGroups(context).filter { characterId in it.characterIds }

    fun loadGroupMessages(context: Context, id: String): List<Message> {
        val file = groupFileFor(context, id)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText())
            Message.listFromJson(json.optJSONArray("messages") ?: return emptyList())
        }.getOrDefault(emptyList())
    }

    fun saveGroup(context: Context, group: Group, messages: List<Message>) {
        val file = groupFileFor(context, group.id)
        val json = group.toJson().apply {
            put("messages", Message.listToJson(messages))
        }
        carryForwardCompactedHistory(json, readJsonFile(file))
        file.writeText(json.toString())
    }

    fun deleteGroup(context: Context, id: String) {
        groupFileFor(context, id).delete()
    }
}
