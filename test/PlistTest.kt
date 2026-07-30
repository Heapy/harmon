import dev.yoda.harmon.setup.PlistValue
import dev.yoda.harmon.setup.PlistXml
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlistTest {
    @Test
    fun escapesEveryXmlMetacharacterInKeysAndValues() {
        val xml = PlistXml.encode(
            PlistValue.Dictionary(
                listOf(
                    """<&"'key""" to PlistValue.StringValue("""<&"'value"""),
                ),
            ),
        )

        assertTrue(xml.contains("&lt;&amp;&quot;&apos;key"))
        assertTrue(xml.contains("&lt;&amp;&quot;&apos;value"))
        assertFalse(xml.contains("""<&"'value"""))
    }

    @Test
    fun encodesOnlyTheSupportedTypedPrimitives() {
        val xml = PlistXml.encode(
            PlistValue.Dictionary(
                listOf(
                    "array" to PlistValue.Array(
                        listOf(
                            PlistValue.IntegerValue(7),
                            PlistValue.BooleanValue(true),
                            PlistValue.BooleanValue(false),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(xml.contains("<integer>7</integer>"))
        assertTrue(xml.contains("<true/>"))
        assertTrue(xml.contains("<false/>"))
    }
}
