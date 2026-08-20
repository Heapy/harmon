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
    fun liveModeRenewsOnlyAVisibleWatchAndKeepsInteractiveTableStateOutsidePayload() {
        val html = ProcessPage.document(payloadJson = "null", mode = "live")

        assertContains(html, "fetch(\"/api/live?watch=1\"")
        assertContains(html, "\"Authorization\": \"Bearer \" + token")
        assertContains(html, "new URLSearchParams(location.hash.slice(1)).getAll(\"token\")")
        assertContains(html, "sessionStorage.setItem(tokenStorageKey, fragmentTokens[0])")
        assertContains(html, "history.replaceState(null, \"\", \"/\")")
        assertContains(html, "sessionStorage.getItem(tokenStorageKey)")
        assertContains(html, "run harmon ui again")
        assertFalse("/api/live?token=" in html)
        assertFalse("location.search" in html)
        assertContains(html, "document.visibilityState === \"visible\"")
        assertContains(html, "visibilitychange")
        assertContains(html, "pollGeneration")
        assertContains(html, "selectedColumns: new Set(presets.Overview)")
        assertContains(html, "sort: { column: \"cpu\", scope: \"total\" }")
        assertContains(html, "const state = {")
        assertContains(html, "state.payload = payload")
    }

    @Test
    fun snapshotModeGatesStorageAndHistoryBootstrapBeforeRendering() {
        val html = ProcessPage.document(payloadJson = "null", mode = "snapshot")

        val liveGate = html.indexOf("if (pageMode === \"live\")")
        assertFalse(liveGate < 0)
        assertFalse(html.indexOf("sessionStorage", startIndex = 0) < liveGate)
        assertFalse(html.indexOf("history.replaceState", startIndex = 0) < liveGate)
    }

    @Test
    fun exposesAllColumnPresetsIndependentSelfTotalSortAndSystemDetails() {
        val html = ProcessPage.document(payloadJson = "null", mode = "snapshot")

        assertContains(html, "[\"Overview\", \"Memory\", \"I/O\", \"Activity\", \"Compute / Energy\"]")
        assertContains(html, "column.label + \" \" + (scope === \"self\" ? \"Self\" : \"Total\")")
        assertContains(html, "columnById[first].selfOnly ? \"self\" : \"total\"")
        assertContains(html, "Lifetime peak")
        assertContains(html, "System details")
        assertContains(html, "Known partial totals remain sortable")
        assertContains(html, "position: sticky; left: 0")
        assertContains(html, "position: sticky; left: var(--pid-width)")
        assertContains(html, "payload.schemaVersion !== 3")
    }

    @Test
    fun keepsIdentitySortAcrossMetricChangesAndPromotesRoundedByteUnits() {
        val html = ProcessPage.document(payloadJson = "null", mode = "snapshot")

        assertContains(
            html,
            "state.sort.scope !== \"identity\" && !state.selectedColumns.has(state.sort.column)",
        )
        assertContains(html, "while (unit < units.length - 1 && tenths >= 10240n)")
    }

    @Test
    fun labelsGlobalAttributionAsTheDatedLastFullCoverage() {
        val html = ProcessPage.document(
            payloadJson = """{"reportText":"1/2 current members with cached values"}""",
            mode = "snapshot",
        )

        assertContains(html, "Last FULL attribution:")
        assertContains(html, "attempts failed · captured")
        assertContains(html, "1/2 current members with cached values")
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
