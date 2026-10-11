package com.gatar.recipedealer.share

import com.gatar.recipedealer.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareRouterTest {
    private fun req(text: String? = null, files: List<SharedFile> = emptyList(), action: String = "android.intent.action.SEND") =
        ShareRequest("id", "2026-10-11T10:00:00Z", action, "text/plain", text, null, null, null, files)

    private fun img(name: String = "a.png") = SharedFile("content://x/$name", "image/png", name)
    private fun pdf(name: String = "a.pdf") = SharedFile("content://x/$name", "application/pdf", name)

    @Test fun bareUrlIsWeb() {
        val r = ShareRouter.route(req("  https://www.kwestiasmaku.com/przepis/szarlotka \n"))!!
        assertEquals(SourceType.WEB, r.type)
        assertEquals("https://www.kwestiasmaku.com/przepis/szarlotka", r.url)
    }

    @Test fun aiChatShareLinksAreWeb() {
        listOf("https://chatgpt.com/s/abc123", "https://share.gemini.google/xyz", "https://claude.ai/share/0f1e")
            .forEach { assertEquals(SourceType.WEB, ShareRouter.route(req(it))!!.type) }
    }

    @Test fun textWithEmbeddedUrlIsText() {
        val r = ShareRouter.route(req("Zobacz ten przepis https://example.com/x"))!!
        assertEquals(SourceType.TEXT, r.type)
        assertNull(r.url)
    }

    @Test fun plainTextIsText() = assertEquals(SourceType.TEXT, ShareRouter.route(req("Składniki\n1 jajko"))!!.type)

    @Test fun nonHttpSchemeIsText() = assertEquals(SourceType.TEXT, ShareRouter.route(req("ftp://example.com/x"))!!.type)

    @Test fun longScreenshotKeepsPageUrl() {
        val r = ShareRouter.route(req("https://example.com/recipe", listOf(img())))!!
        assertEquals(SourceType.IMAGE, r.type)
        assertEquals("https://example.com/recipe", r.url)
        assertEquals(1, r.files.size)
    }

    @Test fun multipleImages() {
        val r = ShareRouter.route(req(files = listOf(img("1.jpg"), img("2.jpg")), action = "android.intent.action.SEND_MULTIPLE"))!!
        assertEquals(SourceType.IMAGE, r.type)
        assertEquals(2, r.files.size)
    }

    @Test fun pdfIsPdf() {
        val r = ShareRouter.route(req(files = listOf(pdf())))!!
        assertEquals(SourceType.PDF, r.type)
    }

    @Test fun pdfByExtensionWhenMimeIsGeneric() {
        val r = ShareRouter.route(req(files = listOf(SharedFile("content://x/1", "application/octet-stream", "Zupa.PDF"))))!!
        assertEquals(SourceType.PDF, r.type)
    }

    @Test fun mixedFilesFollowTheFirst() {
        assertEquals(SourceType.PDF, ShareRouter.route(req(files = listOf(pdf(), img())))!!.type)
        val r = ShareRouter.route(req(files = listOf(img(), pdf())))!!
        assertEquals(SourceType.IMAGE, r.type)
        assertEquals(1, r.notes.size)
    }

    @Test fun nothingToRoute() {
        assertNull(ShareRouter.route(req("   ")))
        assertNull(ShareRouter.route(req(files = listOf(SharedFile("content://x/1", "audio/mp3", "a.mp3")))))
    }
}
