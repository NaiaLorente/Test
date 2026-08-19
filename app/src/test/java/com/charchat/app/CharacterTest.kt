package com.charchat.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterTest {

    @Test
    fun toSystemPrompt_includesCharacterName() {
        val character = Character(name = "Aria")
        val prompt = character.toSystemPrompt()
        assertTrue(prompt.contains("Aria"))
    }

    @Test
    fun toSystemPrompt_fallsBackToGenericNameWhenBlank() {
        val character = Character(name = "")
        val prompt = character.toSystemPrompt()
        assertTrue(prompt.contains("your character"))
    }

    @Test
    fun toSystemPrompt_includesPhysicalDescriptionWhenPresent() {
        val character = Character(name = "Aria", physicalDescription = "tall, red hair")
        val prompt = character.toSystemPrompt()
        assertTrue(prompt.contains("tall, red hair"))
    }

    @Test
    fun toSystemPrompt_omitsPhysicalDescriptionSectionWhenBlank() {
        val character = Character(name = "Aria", physicalDescription = "")
        val prompt = character.toSystemPrompt()
        assertFalse(prompt.contains("Physical appearance of Aria"))
    }

    @Test
    fun toSystemPrompt_includesScenarioAndUserPersonaWhenPresent() {
        val character = Character(
            name = "Aria",
            scenario = "a rainy café",
            userPersona = "a tired traveler"
        )
        val prompt = character.toSystemPrompt()
        assertTrue(prompt.contains("a rainy café"))
        assertTrue(prompt.contains("a tired traveler"))
    }

    @Test
    fun toJson_fromJson_roundTripsAllFields() {
        val original = Character(
            id = "char-1",
            name = "Aria",
            avatarPath = "/path/to/avatar.png",
            physicalDescription = "tall, red hair",
            personality = "cheerful and curious",
            scenario = "a rainy café",
            userPersona = "a tired traveler",
            greeting = "Hey there!",
            creativity = 0.8f
        )

        val restored = Character.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun toJson_fromJson_roundTripsNullAvatarPath() {
        val original = Character(id = "char-2", name = "Bram", avatarPath = null)

        val restored = Character.fromJson(original.toJson())

        assertNull(restored.avatarPath)
    }

    @Test
    fun fromJson_fillsInMissingIdInsteadOfLeavingItBlank() {
        val json = JSONObject().apply {
            put("name", "NoId")
        }

        val restored = Character.fromJson(json)

        assertTrue(restored.id.isNotBlank())
    }

    @Test
    fun fromJson_fallsBackToDefaultCreativityWhenMissing() {
        val json = JSONObject().apply {
            put("id", "char-3")
            put("name", "NoCreativity")
        }

        val restored = Character.fromJson(json)

        assertEquals(DEFAULT_CREATIVITY, restored.creativity)
    }

    @Test
    fun creativityLevelFor_boundaries() {
        assertEquals("Focused", creativityLevelFor(0.2f).label)
        assertEquals("Focused", creativityLevelFor(0.44f).label)
        assertEquals("Balanced", creativityLevelFor(0.45f).label)
        assertEquals("Balanced", creativityLevelFor(0.74f).label)
        assertEquals("Creative", creativityLevelFor(0.75f).label)
        assertEquals("Creative", creativityLevelFor(0.99f).label)
        assertEquals("Wild", creativityLevelFor(1.0f).label)
        assertEquals("Wild", creativityLevelFor(1.2f).label)
    }

    @Test
    fun avatarStyle_isDeterministicForSameId() {
        val character = Character(id = "stable-id", name = "Aria")

        val first = character.avatarStyle()
        val second = character.avatarStyle()

        assertEquals(first, second)
    }

    @Test
    fun avatarStyle_usesUppercasedFirstLetterOfName() {
        val character = Character(id = "stable-id", name = "aria")

        assertEquals("A", character.avatarStyle().letter)
    }

    @Test
    fun avatarStyle_fallsBackToQuestionMarkWhenNameBlank() {
        val character = Character(id = "stable-id", name = "")

        assertEquals("?", character.avatarStyle().letter)
    }
}
