package dev.yoda.harmon.report

/**
 * Produces a self-contained local report without scripts or remote resources.
 */
object ReportHtml {
    fun document(
        title: String,
        subtitle: String,
        reportText: String,
    ): String {
        val escapedTitle = title.escapeHtml()
        val escapedSubtitle = subtitle.escapeHtml()
        val escapedReport = reportText.escapeHtml()

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="light dark">
              <title>$escapedTitle</title>
              <style>
                :root {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  color-scheme: light dark;
                }
                body {
                  margin: 0;
                  background: Canvas;
                  color: CanvasText;
                }
                main {
                  box-sizing: border-box;
                  max-width: 76rem;
                  margin: 0 auto;
                  padding: 2rem;
                }
                h1 {
                  margin: 0;
                  font-size: 1.75rem;
                }
                .subtitle {
                  margin: .5rem 0 1.5rem;
                  color: color-mix(in srgb, CanvasText 65%, transparent);
                }
                pre {
                  margin: 0;
                  padding: 1.25rem;
                  overflow-wrap: anywhere;
                  white-space: pre-wrap;
                  border: 1px solid color-mix(in srgb, CanvasText 18%, transparent);
                  border-radius: .75rem;
                  background: color-mix(in srgb, Canvas 94%, CanvasText);
                  font: 13px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace;
                }
              </style>
            </head>
            <body>
              <main>
                <h1>$escapedTitle</h1>
                <p class="subtitle">$escapedSubtitle</p>
                <pre>$escapedReport</pre>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String = buildString(length) {
        this@escapeHtml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }
}
