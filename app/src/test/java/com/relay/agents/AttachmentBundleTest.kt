package com.relay.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentBundleTest {
    @Test
    fun emptyBundleHasNoContext() {
        assertEquals("", AttachmentBundle().toContext())
    }

    @Test
    fun contextListsFilesTextAndDigest() {
        val b = AttachmentBundle(
            listOf(
                Attachment("notes.txt", "text/plain", 2048, text = "محتوى الملاحظات"),
                Attachment("kitchen.jpg", "image/jpeg", 4096, images = listOf(ImageData("image/jpeg", "AAAA")), note = "صورة")
            ),
            imageDigest = "مطبخ بلون أبيض"
        )
        val c = b.toContext()
        assertTrue(c.contains("notes.txt"))
        assertTrue(c.contains("محتوى الملاحظات"))
        assertTrue(c.contains("kitchen.jpg"))
        assertTrue(c.contains("مطبخ بلون أبيض"))
    }

    @Test
    fun contextRespectsBudget() {
        val big = "x".repeat(50_000)
        val c = AttachmentBundle(listOf(Attachment("big.txt", "text/plain", 50_000, text = big))).toContext(budget = 1_000)
        assertTrue(c.length < 2_000)
    }

    @Test
    fun jsonRoundTrip() {
        val b = AttachmentBundle(
            listOf(Attachment("a.png", "image/png", 10, text = "t", images = listOf(ImageData("image/jpeg", "QQ==")), note = "n")),
            "digest"
        )
        val back = AttachmentBundle.fromJson(JSONObject(b.toJson().toString()))
        assertEquals(b, back)
    }

    @Test
    fun allImagesAreCapped() {
        val imgs = (1..20).map { ImageData("image/jpeg", "i$it") }
        val b = AttachmentBundle(listOf(Attachment("many.zip", "application/zip", 1, images = imgs)))
        assertEquals(AttachmentBundle.MAX_IMAGES, b.allImages.size)
    }
}
