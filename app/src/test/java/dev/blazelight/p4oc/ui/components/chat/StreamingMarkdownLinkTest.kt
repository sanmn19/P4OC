package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownLinkTest {
    private val colors = MarkdownRenderColors(
        text = Color.Black,
        heading = Color.Black,
        strong = Color.Black,
        listMarker = Color.Black,
        listEnumeration = Color.Black,
        muted = Color.Gray,
        border = Color.Gray,
        codeBackground = Color.DarkGray,
        inlineCodeBackground = Color.DarkGray,
        link = Color.Blue,
    )

    @Test
    fun `bare url is a link`() {
        val text = inlineMarkdown("see https://example.com/x now", colors)

        assertEquals(listOf("https://example.com/x"), linkUrls(text))
    }

    @Test
    fun `markdown link uses its target`() {
        val text = inlineMarkdown("see [docs](https://example.org/guide) now", colors)

        assertEquals(listOf("https://example.org/guide"), linkUrls(text))
    }

    @Test
    fun `url inside inline code is still a link`() {
        val text = inlineMarkdown("run `http://10.0.0.2:4096` now", colors)

        assertEquals(listOf("http://10.0.0.2:4096"), linkUrls(text))
    }

    @Test
    fun `unsafe markdown link target is not a link`() {
        val text = inlineMarkdown("[x](javascript:alert(1))", colors)

        assertTrue(linkUrls(text).isEmpty())
    }

    @Test
    fun `trailing punctuation is not part of the link`() {
        val text = inlineMarkdown("go to https://example.com/a.", colors)

        assertEquals(listOf("https://example.com/a"), linkUrls(text))
    }

    private fun linkUrls(text: AnnotatedString): List<String> =
        text.getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
}
