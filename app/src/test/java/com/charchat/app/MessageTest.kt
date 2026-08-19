package com.charchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTest {

    @Test
    fun toJson_fromJson_roundTripsUserMessage() {
        val original = Message(id = "msg-1", content = "Hello there", isUser = true)

        val restored = Message.fromJson(original.toJson())

        assertEquals(original.id, restored.id)
        assertEquals(original.content, restored.content)
        assertEquals(original.isUser, restored.isUser)
        assertNull(restored.speakerId)
    }

    @Test
    fun toJson_fromJson_roundTripsAssistantMessageWithSpeaker() {
        val original = Message(id = "msg-2", content = "Hi!", isUser = false, speakerId = "aria-id")

        val restored = Message.fromJson(original.toJson())

        assertEquals("aria-id", restored.speakerId)
        assertEquals(original.isUser, restored.isUser)
    }

    @Test
    fun listToJson_listFromJson_roundTripsInOrder() {
        val original = listOf(
            Message(id = "msg-1", content = "Hi", isUser = true),
            Message(id = "msg-2", content = "Hello!", isUser = false, speakerId = "aria-id"),
            Message(id = "msg-3", content = "How are you?", isUser = true)
        )

        val restored = Message.listFromJson(Message.listToJson(original))

        assertEquals(original.map { it.id }, restored.map { it.id })
        assertEquals(original.map { it.content }, restored.map { it.content })
        assertEquals(original.map { it.isUser }, restored.map { it.isUser })
        assertEquals(original.map { it.speakerId }, restored.map { it.speakerId })
    }

    @Test
    fun listToJson_listFromJson_roundTripsEmptyList() {
        val restored = Message.listFromJson(Message.listToJson(emptyList()))

        assertEquals(emptyList<Message>(), restored)
    }
}
