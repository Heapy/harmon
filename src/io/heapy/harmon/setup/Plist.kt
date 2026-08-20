package io.heapy.harmon.setup

sealed interface PlistValue {
    data class Dictionary(
        val entries: List<Pair<String, PlistValue>>,
    ) : PlistValue

    data class Array(
        val values: List<PlistValue>,
    ) : PlistValue

    data class StringValue(
        val value: String,
    ) : PlistValue

    data class IntegerValue(
        val value: Long,
    ) : PlistValue

    data class BooleanValue(
        val value: Boolean,
    ) : PlistValue
}

object PlistXml {
    fun encode(root: PlistValue.Dictionary): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" """ +
                """"http://www.apple.com/DTDs/PropertyList-1.0.dtd">""",
        )
        appendLine("""<plist version="1.0">""")
        appendValue(root, depth = 0)
        appendLine("</plist>")
    }

    private fun StringBuilder.appendValue(value: PlistValue, depth: Int) {
        when (value) {
            is PlistValue.Dictionary -> {
                appendIndent(depth)
                appendLine("<dict>")
                value.entries.forEach { (key, child) ->
                    appendIndent(depth + 1)
                    append("<key>")
                    append(escapeXml(key))
                    appendLine("</key>")
                    appendValue(child, depth + 1)
                }
                appendIndent(depth)
                appendLine("</dict>")
            }
            is PlistValue.Array -> {
                appendIndent(depth)
                appendLine("<array>")
                value.values.forEach { child -> appendValue(child, depth + 1) }
                appendIndent(depth)
                appendLine("</array>")
            }
            is PlistValue.StringValue -> {
                appendIndent(depth)
                append("<string>")
                append(escapeXml(value.value))
                appendLine("</string>")
            }
            is PlistValue.IntegerValue -> {
                appendIndent(depth)
                append("<integer>")
                append(value.value)
                appendLine("</integer>")
            }
            is PlistValue.BooleanValue -> {
                appendIndent(depth)
                appendLine(if (value.value) "<true/>" else "<false/>")
            }
        }
    }

    private fun StringBuilder.appendIndent(depth: Int) {
        repeat(depth) { append(INDENT) }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    private const val INDENT = "    "
}
