package com.charchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTest {

    private val aria = Character(id = "aria-id", name = "Aria")
    private val bram = Character(id = "bram-id", name = "Bram")

    @Test
    fun toSystemPrompt_includesEveryMembersName() {
        val group = Group(characterIds = listOf(aria.id, bram.id))

        val prompt = group.toSystemPrompt(listOf(aria, bram))

        assertTrue(prompt.contains("Aria"))
        assertTrue(prompt.contains("Bram"))
    }

    @Test
    fun toSystemPrompt_includesEachMembersOwnDescription() {
        val ariaWithTraits = aria.copy(physicalDescription = "tall, red hair", personality = "cheerful")
        val bramWithTraits = bram.copy(physicalDescription = "short, dark hair", personality = "gruff")
        val group = Group(characterIds = listOf(aria.id, bram.id))

        val prompt = group.toSystemPrompt(listOf(ariaWithTraits, bramWithTraits))

        assertTrue(prompt.contains("tall, red hair"))
        assertTrue(prompt.contains("cheerful"))
        assertTrue(prompt.contains("short, dark hair"))
        assertTrue(prompt.contains("gruff"))
    }

    @Test
    fun toSystemPrompt_includesScenarioWhenPresent() {
        val group = Group(characterIds = listOf(aria.id, bram.id), scenario = "a rainy café")

        val prompt = group.toSystemPrompt(listOf(aria, bram))

        assertTrue(prompt.contains("a rainy café"))
    }

    @Test
    fun displayName_usesExplicitNameWhenSet() {
        val group = Group(name = "The Café Crew", characterIds = listOf(aria.id, bram.id))

        assertEquals("The Café Crew", group.displayName(listOf(aria, bram)))
    }

    @Test
    fun displayName_fallsBackToJoinedMemberNamesWhenBlank() {
        val group = Group(name = "", characterIds = listOf(aria.id, bram.id))

        assertEquals("Aria, Bram", group.displayName(listOf(aria, bram)))
    }

    @Test
    fun toJson_fromJson_roundTripsAllFields() {
        val original = Group(
            id = "group-1",
            name = "The Café Crew",
            characterIds = listOf(aria.id, bram.id),
            scenario = "a rainy café",
            creativity = 0.9f
        )

        val restored = Group.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun fromJson_fillsInMissingIdInsteadOfLeavingItBlank() {
        val original = Group(id = "", name = "NoId", characterIds = listOf(aria.id))

        val restored = Group.fromJson(original.toJson())

        assertTrue(restored.id.isNotBlank())
    }
}
