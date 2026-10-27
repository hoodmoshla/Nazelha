package com.snapnet.download.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageLinkDiscoveryTest {
    @Test
    fun `discovers explicit files and resolves relative links`() {
        val links =
            WebPageLinkDiscovery.extract(
                """
                <html><body>
                  <a href="files/report.pdf?token=keep-private">تقرير عربي</a>
                  <a href="https://cdn.example/video.mp4">فيديو</a>
                  <a href="/page.html">صفحة ويب</a>
                </body></html>
                """.trimIndent(),
                "https://site.example/articles/current",
            )

        assertEquals(2, links.size)
        assertEquals("https://site.example/articles/files/report.pdf?token=keep-private", links[0].url)
        assertEquals("تقرير عربي", links[0].label)
        assertEquals("site.example", links[0].host)
        assertEquals("https://cdn.example/video.mp4", links[1].url)
    }

    @Test
    fun `accepts explicit download attribute without a file extension`() {
        val links =
            WebPageLinkDiscovery.extract(
                "<a download href='/get?id=42'>تنزيل</a><a href='/ordinary'>عادي</a>",
                "https://site.example/start",
            )

        assertEquals(1, links.size)
        assertEquals("https://site.example/get?id=42", links.single().url)
    }

    @Test
    fun `rejects non-http schemes and credential-bearing links`() {
        val links =
            WebPageLinkDiscovery.extract(
                """
                <a href="javascript:alert(1)">script</a>
                <a href="mailto:person@example.com">mail</a>
                <a href="file:///etc/passwd">local</a>
                <a href="https://user:pass@cdn.example/file.pdf">credentials</a>
                <a href="https://cdn.example/file.pdf">valid</a>
                """.trimIndent(),
                "https://site.example/",
            )

        assertEquals(1, links.size)
        assertEquals("https://cdn.example/file.pdf", links.single().url)
    }

    @Test
    fun `deduplicates identical targets and caps results`() {
        val html = buildString {
            append("<a href='/same.pdf'>first</a><a href='/same.pdf'>duplicate</a>")
            repeat(WebPageLinkDiscovery.MAX_LINKS + 5) { append("<a href='/f$it.pdf'>file $it</a>") }
        }

        val links = WebPageLinkDiscovery.extract(html, "https://site.example/")

        assertEquals(WebPageLinkDiscovery.MAX_LINKS, links.size)
        assertEquals(1, links.count { it.url.endsWith("/same.pdf") })
        assertTrue(links.all { it.host == "site.example" })
    }

    @Test
    fun `ignores oversized HTML input`() {
        val html = "x".repeat(WebPageLinkDiscovery.MAX_HTML_BYTES + 1) + "<a href='/file.pdf'>file</a>"

        assertTrue(WebPageLinkDiscovery.extract(html, "https://site.example/").isEmpty())
    }
}
