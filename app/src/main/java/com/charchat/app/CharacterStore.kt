package com.charchat.app

import android.content.Context
import org.json.JSONObject

object CharacterStore {
    private const val PREFS = "charchat_prefs"
    private const val KEY_CHARACTER = "character_json"

    fun save(context: Context, character: Character) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHARACTER, character.toJson().toString())
            .apply()
    }

    fun load(context: Context): Character? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHARACTER, null) ?: return null
        return runCatching { Character.fromJson(JSONObject(raw)) }.getOrNull()
    }
}
