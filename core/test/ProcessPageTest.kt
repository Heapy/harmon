import dev.yoda.harmon.report.PREACT_VERSION
import dev.yoda.harmon.report.ProcessPage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ProcessPageTest {
    @Test
    fun embedsPinnedPreactWithoutABundlerOrRemoteResource() {
        val html = ProcessPage.document(payloadJson = "null", mode = "snapshot")

        assertContains(html, "Vendored Preact $PREACT_VERSION")
        assertContains(html, "data:text/javascript;base64,")
        assertContains(html, "globalThis.preact")
        assertFalse("type=\"importmap\"" in html)
        assertFalse("https://" in html)
        assertFalse("http://" in html)
    }

    @Test
    fun escapesBootstrapJsonAndTheNoScriptFallbackInTheirOwnContexts() {
        val hostile = "</script><script id=attack>&\u2028"

        val html = ProcessPage.document(
            payloadJson = "{\"name\":\"$hostile\"}",
            mode = "snapshot\" autofocus onfocus=\"attack()",
            title = "<title>&",
            fallbackText = hostile,
        )

        assertFalse("<script id=attack>" in html)
        assertContains(html, "\\u003c/script>\\u003cscript id=attack>\\u0026\\u2028")
        assertContains(html, "<title>&lt;title&gt;&amp;</title>")
        assertContains(html, "&lt;/script&gt;&lt;script id=attack&gt;&amp;")
        assertContains(html, "data-mode=\"snapshot&quot; autofocus onfocus=&quot;attack()\"")
    }
}
