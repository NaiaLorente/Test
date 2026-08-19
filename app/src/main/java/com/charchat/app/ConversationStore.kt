package com.charchat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One entry of the model's native chat history after the pinned system message: role, exact
 * text, and its real KV-cache boundary position - only meaningful (not -1) when this snapshot was
 * captured alongside a raw [contextStateFile], to rebuild bookkeeping after restoring it.
 */
data class HistoryEntry(val role: String, val content: String, val endPosition: Int = -1)

/**
 * Everything needed to validate and use a saved raw context state ([contextStateFile]): which
 * model it was saved against, a hash of the system prompt that was live at save time (so an
 * edited character/group, or a changed member list, correctly invalidates it), the pinned system
 * message's own end position, the compacted history entries (with real KV-cache positions) to
 * rebuild bookkeeping after restoring the raw state, and how many of the full persisted message
 * list this snapshot covers (a stale save from before the most recent messages just means the gap
 * is seeded the normal way on top of it).
 */
data class ContextStateMetadata(
    val modelName: String,
    val systemPromptHash: String,
    val systemPromptPosition: Int,
    val entries: List<HistoryEntry>,
    val coveredMessageCount: Int
)

/**
 * Persists characters, groups, and their conversations to disk - one JSON file per character or
 * group, in separate directories, so nothing is lost when the app is closed and reopened, and
 * groups never get parsed as (garbage) characters or vice versa.
 */
object ConversationStore {
    private const val CHARACTERS_DIRECTORY = "characters"
    private const val GROUPS_DIRECTORY = "groups"
    private const val CONTEXT_STATE_DIRECTORY = "context_state"

    private fun directory(context: Context, name: String) =
        File(context.filesDir, name).also { if (!it.exists()) it.mkdirs() }

    private fun charactersDirectory(context: Context) = directory(context, CHARACTERS_DIRECTORY)
    private fun groupsDirectory(context: Context) = directory(context, GROUPS_DIRECTORY)
    private fun contextStateDirectory(context: Context) = directory(context, CONTEXT_STATE_DIRECTORY)

    private fun fileFor(context: Context, id: String) = File(charactersDirectory(context), "$id.json")
    private fun groupFileFor(context: Context, id: String) = File(groupsDirectory(context), "$id.json")

    /** Where the engine's raw context state (KV-cache) gets saved to and restored from. */
    fun contextStateFile(context: Context, characterId: String): File =
        File(contextStateDirectory(context), "character-$characterId.state")

    fun groupContextStateFile(context: Context, groupId: String): File =
        File(contextStateDirectory(context), "group-$groupId.state")

    private const val COMPACTED_HISTORY_KEY = "compactedHistory"
    private const val COMPACTED_HISTORY_COUNT_KEY = "compactedHistoryCount"
    private const val CONTEXT_STATE_MODEL_KEY = "contextStateModelName"
    private const val CONTEXT_STATE_PROMPT_HASH_KEY = "contextStateSystemPromptHash"
    private const val CONTEXT_STATE_PROMPT_POSITION_KEY = "contextStateSystemPromptPosition"
    // Deliberately separate from COMPACTED_HISTORY_KEY/COUNT (a different, older snapshot that
    // replayConversation() re-saves after *every* replay, including ones that used a fast restore)
    // - these must only ever change in lockstep with the raw .state file itself, or a restore could
    // apply entries/coverage the .state file's actual KV-cache doesn't yet contain (e.g. if the app
    // is killed by a crash between one onStop() save and the next, after more messages were sent).
    private const val CONTEXT_STATE_HISTORY_KEY = "contextStateHistory"
    private const val CONTEXT_STATE_HISTORY_COUNT_KEY = "contextStateHistoryCount"

    private fun readJsonFile(file: File): JSONObject? =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null

    /**
     * A plain [save]/[saveGroup] only knows about the character/group fields and the raw message
     * list - carry forward whatever compacted-history/context-state metadata [saveCompactedHistory]
     * or [saveContextStateMetadata] previously wrote for this file, so an ordinary message-list
     * persist doesn't silently erase it.
     */
    private fun carryForwardCompactedHistory(target: JSONObject, existing: JSONObject?) {
        existing ?: return
        for (key in listOf(COMPACTED_HISTORY_KEY, CONTEXT_STATE_HISTORY_KEY)) {
            existing.optJSONArray(key)?.let { target.put(key, it) }
        }
        for (key in listOf(COMPACTED_HISTORY_COUNT_KEY, CONTEXT_STATE_PROMPT_POSITION_KEY, CONTEXT_STATE_HISTORY_COUNT_KEY)) {
            if (existing.has(key)) target.put(key, existing.optInt(key))
        }
        for (key in listOf(CONTEXT_STATE_MODEL_KEY, CONTEXT_STATE_PROMPT_HASH_KEY)) {
            if (existing.has(key)) target.put(key, existing.optString(key))
        }
    }

    private fun parseHistoryEntries(array: JSONArray): List<HistoryEntry> =
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            HistoryEntry(obj.optString("role"), obj.optString("content"), obj.optInt("endPosition", -1))
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

    /** See [ContextStateMetadata]. Null if no raw context state was ever saved for this file. */
    private fun loadContextStateMetadata(file: File): ContextStateMetadata? {
        val json = readJsonFile(file) ?: return null
        val modelName = json.optString(CONTEXT_STATE_MODEL_KEY, "").takeIf { it.isNotBlank() } ?: return null
        val hash = json.optString(CONTEXT_STATE_PROMPT_HASH_KEY, "").takeIf { it.isNotBlank() } ?: return null
        val position = json.optInt(CONTEXT_STATE_PROMPT_POSITION_KEY, -1)
        val count = json.optInt(CONTEXT_STATE_HISTORY_COUNT_KEY, -1)
        if (position <= 0 || count <= 0) return null
        val array = json.optJSONArray(CONTEXT_STATE_HISTORY_KEY) ?: return null
        return runCatching { ContextStateMetadata(modelName, hash, position, parseHistoryEntries(array), count) }.getOrNull()
    }

    private fun saveContextStateMetadata(
        file: File,
        modelName: String,
        systemPromptHash: String,
        systemPromptPosition: Int,
        historyJson: String,
        coveredMessageCount: Int
    ) {
        val json = readJsonFile(file) ?: return // character/group must already exist on disk
        runCatching {
            json.put(CONTEXT_STATE_MODEL_KEY, modelName)
            json.put(CONTEXT_STATE_PROMPT_HASH_KEY, systemPromptHash)
            json.put(CONTEXT_STATE_PROMPT_POSITION_KEY, systemPromptPosition)
            json.put(CONTEXT_STATE_HISTORY_KEY, JSONArray(historyJson))
            json.put(CONTEXT_STATE_HISTORY_COUNT_KEY, coveredMessageCount)
            file.writeText(json.toString())
        }
    }

    fun loadContextStateMetadata(context: Context, characterId: String) =
        loadContextStateMetadata(fileFor(context, characterId))

    fun saveContextStateMetadata(
        context: Context,
        characterId: String,
        modelName: String,
        systemPromptHash: String,
        systemPromptPosition: Int,
        historyJson: String,
        coveredMessageCount: Int
    ) = saveContextStateMetadata(
        fileFor(context, characterId), modelName, systemPromptHash, systemPromptPosition, historyJson, coveredMessageCount
    )

    fun loadGroupContextStateMetadata(context: Context, groupId: String) =
        loadContextStateMetadata(groupFileFor(context, groupId))

    fun saveGroupContextStateMetadata(
        context: Context,
        groupId: String,
        modelName: String,
        systemPromptHash: String,
        systemPromptPosition: Int,
        historyJson: String,
        coveredMessageCount: Int
    ) = saveContextStateMetadata(
        groupFileFor(context, groupId), modelName, systemPromptHash, systemPromptPosition, historyJson, coveredMessageCount
    )

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
        contextStateFile(context, id).delete()
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
        groupContextStateFile(context, id).delete()
    }
}
