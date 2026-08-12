package dev.yoda.harmon.report

object ProcessPage {
    fun document(
        payloadJson: String?,
        mode: String,
        title: String = "Harmon process monitor",
        fallbackText: String = "JavaScript is required to render the process tree.",
    ): String {
        val bootstrap = payloadJson?.escapeScriptData() ?: "null"
        val escapedTitle = title.escapeHtml()
        val escapedFallback = fallbackText.escapeHtml()
        val escapedMode = mode.escapeHtml()
        return $$"""
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="dark">
              <meta name="referrer" content="no-referrer">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'">
              <title>$$escapedTitle</title>
              <!-- Vendored Preact $$PREACT_VERSION, MIT; see third_party/preact/LICENSE or release PREACT-LICENSE. -->
              <style>$$STYLE</style>
              <script src="data:text/javascript;base64,$$PREACT_UMD_BASE64"></script>
            </head>
            <body data-mode="$$escapedMode">
              <script id="harmon-bootstrap" type="application/json">$$bootstrap</script>
              <main id="app">
                <p class="loading">Loading process metrics…</p>
              </main>
              <noscript><section class="raw-report"><pre>$$escapedFallback</pre></section></noscript>
              <script>$$APP</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeScriptData(): String =
        replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    private fun String.escapeHtml(): String = buildString(length) {
        for (character in this@escapeHtml) {
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

    private val STYLE = $$"""
        :root {
          color-scheme: dark;
          font-family: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
          font-variant-numeric: tabular-nums;
          background: #090c10;
          color: #d8dee9;
          --panel: #10151c;
          --panel-2: #151c25;
          --line: #26313f;
          --muted: #8391a5;
          --cyan: #65d8e6;
          --green: #83df9a;
          --amber: #f0bd66;
          --red: #ff7a90;
        }

        * { box-sizing: border-box; }

        body { margin: 0; min-width: 760px; background: #090c10; }

        button, input, select { font: inherit; }

        button:focus-visible, input:focus-visible, select:focus-visible {
          outline: 2px solid var(--cyan);
          outline-offset: 2px;
        }

        .shell { min-height: 100vh; padding: 18px 22px 32px; }

        .topbar {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          gap: 24px;
          margin-bottom: 14px;
        }

        .eyebrow {
          margin: 0 0 4px;
          color: var(--cyan);
          font: 700 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .13em;
          text-transform: uppercase;
        }

        h1 { margin: 0; color: #f5f7fa; font-size: 24px; letter-spacing: -.025em; }

        .subtitle { margin: 5px 0 0; color: var(--muted); font-size: 13px; }

        .mode-button {
          min-width: 138px;
          border: 1px solid #344255;
          border-radius: 7px;
          padding: 8px 12px;
          background: #17202a;
          color: #eff4fa;
          cursor: pointer;
        }

        .mode-button[data-live="true"] { border-color: #267a58; color: var(--green); }
        .mode-button:disabled { cursor: default; opacity: .72; }

        .summary-grid {
          display: grid;
          grid-template-columns: repeat(5, minmax(118px, 1fr));
          gap: 8px;
          margin-bottom: 10px;
        }

        .summary-card {
          min-height: 65px;
          border: 1px solid var(--line);
          border-radius: 8px;
          padding: 9px 11px;
          background: linear-gradient(145deg, #121922, #0e131a);
        }

        .summary-label {
          display: block;
          margin-bottom: 6px;
          color: var(--muted);
          font: 700 10px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .08em;
          text-transform: uppercase;
        }

        .summary-value { color: #f3f6f9; font-size: 18px; font-weight: 650; }
        .summary-detail { margin-left: 5px; color: var(--muted); font-size: 11px; }

        .notice {
          margin: 0 0 10px;
          border: 1px solid #6f5125;
          border-radius: 7px;
          padding: 8px 10px;
          background: #211a10;
          color: #ffd897;
          font-size: 12px;
        }

        .notice.error { border-color: #713547; background: #25131a; color: #ffb0bf; }

        .toolbar {
          display: flex;
          align-items: center;
          gap: 9px;
          border: 1px solid var(--line);
          border-bottom: 0;
          border-radius: 9px 9px 0 0;
          padding: 8px;
          background: var(--panel);
        }

        .search {
          flex: 1;
          min-width: 240px;
          border: 1px solid #354254;
          border-radius: 6px;
          padding: 7px 10px;
          background: #0b1016;
          color: #edf2f7;
        }

        .search::placeholder { color: #66758a; }

        .toolbar-meta {
          white-space: nowrap;
          color: var(--muted);
          font: 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        .table-wrap {
          overflow: auto;
          border: 1px solid var(--line);
          border-radius: 0 0 9px 9px;
          background: #0c1117;
        }

        table { width: 100%; border-collapse: collapse; table-layout: fixed; }

        thead { position: sticky; top: 0; z-index: 3; }

        th {
          border-bottom: 1px solid #334052;
          padding: 0;
          background: #171e27;
          color: #9eacbd;
          text-align: right;
          font: 700 10px/1.1 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .06em;
          text-transform: uppercase;
        }

        th:first-child, th:nth-child(2) { text-align: left; }

        .sort-button {
          width: 100%;
          border: 0;
          padding: 9px 10px;
          background: transparent;
          color: inherit;
          text-align: inherit;
          cursor: pointer;
        }

        .sort-button[aria-pressed="true"] { color: var(--cyan); }

        td {
          position: relative;
          height: 34px;
          border-bottom: 1px solid #18212b;
          padding: 0 10px;
          overflow: hidden;
          color: #cad2dd;
          text-align: right;
          text-overflow: ellipsis;
          white-space: nowrap;
          font: 12px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        td:first-child, td:nth-child(2) { text-align: left; }
        tbody tr:hover td { background-color: #121a24; }
        tbody tr.unavailable td { color: #b79c6e; }

        .pid { width: 84px; color: #91a0b4; }
        .process-column { width: 42%; min-width: 330px; }
        .metric-column { width: 14.5%; min-width: 125px; }

        .tree-cell { display: flex; align-items: center; min-width: 0; height: 34px; }

        .tree-guide { flex: none; width: calc(var(--depth) * 18px); }

        .twisty, .twisty-space {
          flex: none;
          width: 24px;
          height: 26px;
          margin-right: 2px;
        }

        .twisty {
          border: 0;
          border-radius: 4px;
          background: transparent;
          color: #8191a6;
          cursor: pointer;
        }

        .twisty:hover { background: #24303e; color: #dbe5ef; }
        .process-name { overflow: hidden; text-overflow: ellipsis; color: #eef2f6; }
        .partial { margin-left: 7px; color: var(--amber); font-size: 10px; }
        .issue { margin-left: 7px; color: #b79c6e; font-size: 10px; text-transform: lowercase; }

        .metric-bar {
          position: absolute;
          inset: 4px 5px;
          width: min(var(--fill), calc(100% - 10px));
          border-radius: 3px;
          background: color-mix(in srgb, var(--bar-color) 20%, transparent);
          pointer-events: none;
        }

        .metric-value { position: relative; z-index: 1; }
        .self { --bar-color: #718096; color: #aab5c2; }
        .total { --bar-color: var(--cyan); color: #e5fbff; font-weight: 700; }
        .cpu-total { --bar-color: var(--green); }

        .empty { padding: 42px 20px; color: var(--muted); text-align: center; }
        .loading { padding: 40px; color: var(--muted); }
        .raw-report { padding: 24px; }
        .raw-report pre { white-space: pre-wrap; font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }

        .report-details {
          margin-top: 12px;
          border: 1px solid var(--line);
          border-radius: 8px;
          background: var(--panel);
        }

        .report-details summary {
          padding: 10px 12px;
          color: var(--muted);
          cursor: pointer;
          font: 700 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .05em;
          text-transform: uppercase;
        }

        .report-details pre {
          max-height: 420px;
          margin: 0;
          padding: 0 12px 14px;
          overflow: auto;
          white-space: pre-wrap;
          color: #bec8d4;
          font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        @media (max-width: 980px) {
          .summary-grid { grid-template-columns: repeat(3, 1fr); }
        }

        @media (prefers-reduced-motion: no-preference) {
          .mode-button, .twisty, tbody td { transition: background-color 120ms, color 120ms; }
        }
    """.trimIndent()

    private val APP = $$"""
        const { Fragment, h, render } = globalThis.preact;

        const root = document.getElementById("app");
        const bootstrapNode = document.getElementById("harmon-bootstrap");
        const pageMode = document.body.dataset.mode || "live";
        const token = new URLSearchParams(location.search).get("token") || "";
        const e = (type, props, ...children) => h(type, props, ...children);
        const initial = JSON.parse(bootstrapNode.textContent || "null");

        const state = {
          payload: initial,
          frozen: pageMode !== "live",
          search: "",
          sortKey: "memoryTotalBytes",
          sortDirection: "desc",
          expanded: new Set(),
          initializedExpansion: false,
          requestError: null,
          timer: null,
          pollGeneration: 0
        };

        function asBigInt(value) {
          try { return BigInt(value || "0"); } catch (_) { return 0n; }
        }

        function finite(value) {
          return Number.isFinite(value) && value >= 0 ? value : 0;
        }

        function formatBytes(value) {
          const bytes = asBigInt(value);
          const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
          let unit = 0;
          let divisor = 1n;
          while (unit < units.length - 1 && bytes >= divisor * 1024n) {
            divisor *= 1024n;
            unit += 1;
          }
          if (unit === 0) return bytes.toString() + " B";
          const tenths = (bytes * 10n + divisor / 2n) / divisor;
          return (tenths / 10n).toString() + "." + (tenths % 10n).toString() + " " + units[unit];
        }

        function formatCpu(value) {
          const cpu = finite(value);
          return cpu >= 100 ? cpu.toFixed(0) + "%" : cpu.toFixed(1) + "%";
        }

        function compareValues(a, b, key) {
          if (key === "memorySelfBytes" || key === "memoryTotalBytes") {
            const left = asBigInt(a[key]);
            const right = asBigInt(b[key]);
            return left < right ? -1 : left > right ? 1 : 0;
          }
          if (key === "name") return a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
          return finite(a[key]) - finite(b[key]);
        }

        function nodeComparator(a, b) {
          let compared = compareValues(a, b, state.sortKey);
          if (state.sortDirection === "desc") compared = -compared;
          if (compared !== 0) return compared;
          const byName = a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
          return byName !== 0 ? byName : a.pid - b.pid;
        }

        function sortTree(nodes) {
          return [...nodes].sort(nodeComparator).map(node => ({
            ...node,
            children: sortTree(node.children || [])
          }));
        }

        function matches(node, query) {
          return node.name.toLocaleLowerCase().includes(query) || String(node.pid).includes(query);
        }

        function filterNode(node, query, ancestorMatched) {
          const selfMatched = matches(node, query);
          if (ancestorMatched || selfMatched) return node;
          const children = node.children.map(child => filterNode(child, query, false)).filter(Boolean);
          return children.length ? { ...node, children } : null;
        }

        function visibleRoots(tree) {
          const sorted = sortTree(tree.roots || []);
          const query = state.search.trim().toLocaleLowerCase();
          if (!query) return sorted;
          return sorted.map(node => filterNode(node, query, false)).filter(Boolean);
        }

        function initializeExpansion(tree) {
          if (state.initializedExpansion || !tree) return;
          for (const rootNode of tree.roots || []) {
            state.expanded.add(rootNode.key);
            for (const child of rootNode.children || []) state.expanded.add(child.key);
          }
          state.initializedExpansion = true;
        }

        function setSort(key) {
          if (state.sortKey === key) {
            state.sortDirection = state.sortDirection === "desc" ? "asc" : "desc";
          } else {
            state.sortKey = key;
            state.sortDirection = key === "name" || key === "pid" ? "asc" : "desc";
          }
          draw();
        }

        function sortHeader(label, key, className) {
          const active = state.sortKey === key;
          const suffix = active ? (state.sortDirection === "desc" ? " ▼" : " ▲") : "";
          return e("th", {
            class: className,
            "aria-sort": active ? (state.sortDirection === "desc" ? "descending" : "ascending") : "none"
          },
            e("button", {
              class: "sort-button",
              type: "button",
              "aria-label": "Sort by " + label,
              onClick: () => setSort(key)
            }, label + suffix)
          );
        }

        function metricCell(value, formatted, classes, maximum) {
          const ratio = maximum > 0 ? Math.min(100, finite(value) / maximum * 100) : 0;
          return e("td", { class: classes },
            e("span", { class: "metric-bar", style: { "--fill": ratio.toFixed(2) + "%" } }),
            e("span", { class: "metric-value" }, formatted)
          );
        }

        function memoryCell(value, classes, maximum) {
          const max = maximum > 0n ? maximum : 1n;
          const ratio = Number(asBigInt(value) * 10000n / max) / 100;
          return e("td", { class: classes },
            e("span", { class: "metric-bar", style: { "--fill": Math.min(100, ratio).toFixed(2) + "%" } }),
            e("span", { class: "metric-value" }, formatBytes(value))
          );
        }

        function flatten(nodes, depth, forceOpen, rows) {
          for (const node of nodes) {
            rows.push({ node, depth });
            const open = forceOpen || state.expanded.has(node.key);
            if (open) flatten(node.children || [], depth + 1, forceOpen, rows);
          }
          return rows;
        }

        function ProcessRows({ roots }) {
          const forceOpen = state.search.trim().length > 0;
          const rows = flatten(roots, 0, forceOpen, []);
          const maxCpuSelf = Math.max(1, ...rows.map(row => finite(row.node.cpuSelfPercent)));
          const maxCpuTotal = Math.max(1, ...rows.map(row => finite(row.node.cpuTotalPercent)));
          const maxMemorySelf = rows.reduce((max, row) => {
            const value = asBigInt(row.node.memorySelfBytes);
            return value > max ? value : max;
          }, 1n);
          const maxMemoryTotal = rows.reduce((max, row) => {
            const value = asBigInt(row.node.memoryTotalBytes);
            return value > max ? value : max;
          }, 1n);

          return rows.map(({ node, depth }) => {
            const hasChildren = node.children && node.children.length > 0;
            const open = forceOpen || state.expanded.has(node.key);
            const toggleLabel = (open ? "Collapse " : "Expand ") + node.name + " (PID " + node.pid + ")";
            return e("tr", {
              key: node.key,
              class: node.measured ? "" : "unavailable",
              "data-pid": String(node.pid),
              "data-process-key": node.key,
              "aria-level": String(depth + 1),
              "aria-expanded": hasChildren ? String(open) : null
            },
              e("td", { class: "pid" }, String(node.pid)),
              e("td", null,
                e("div", { class: "tree-cell", style: { "--depth": String(depth) } },
                  e("span", { class: "tree-guide" }),
                  hasChildren ? e("button", {
                    class: "twisty",
                    type: "button",
                    "aria-label": toggleLabel,
                    "aria-expanded": String(open),
                    onClick: () => {
                      if (state.expanded.has(node.key)) state.expanded.delete(node.key);
                      else state.expanded.add(node.key);
                      draw();
                    }
                  }, open ? "▾" : "▸") : e("span", { class: "twisty-space" }),
                  e("span", { class: "process-name", title: node.executablePath || node.name }, node.name),
                  node.totalsPartial ? e("span", {
                    class: "partial",
                    title: String(node.unavailableProcessCount) + " unavailable process(es) excluded from totals"
                  }, "partial") : null,
                  !node.measured ? e("span", { class: "issue" }, String(node.issueReason || "unavailable").replaceAll("_", " ")) : null
                )
              ),
              metricCell(node.cpuSelfPercent, formatCpu(node.cpuSelfPercent), "self", maxCpuSelf),
              metricCell(node.cpuTotalPercent, formatCpu(node.cpuTotalPercent), "total cpu-total", maxCpuTotal),
              memoryCell(node.memorySelfBytes, "self", maxMemorySelf),
              memoryCell(node.memoryTotalBytes, "total", maxMemoryTotal)
            );
          });
        }

        function summaryCard(label, value, detail) {
          return e("div", { class: "summary-card" },
            e("span", { class: "summary-label" }, label),
            e("span", { class: "summary-value" }, value),
            detail ? e("span", { class: "summary-detail" }, detail) : null
          );
        }

        function statusText(payload) {
          if (!payload) return "Waiting for the first sample";
          if (payload.status === "STALE") {
            const since = payload.staleSince ? new Date(payload.staleSince).getTime() : Date.now();
            return "Metrics stale for " + Math.max(0, Math.floor((Date.now() - since) / 1000)) + "s";
          }
          if (payload.status === "WARMING") return "Collecting the baseline sample";
          return null;
        }

        function App() {
          const payload = state.payload;
          const tree = payload && payload.processTree;
          initializeExpansion(tree);
          const roots = tree ? visibleRoots(tree) : [];
          const system = payload && payload.system;
          const status = statusText(payload);
          const captured = tree && tree.capturedAt ? new Date(tree.capturedAt).toLocaleTimeString() : "—";
          const displayed = tree ? tree.displayedProcessCount : 0;
          const total = tree ? tree.totalProcessCount : 0;
          const unavailable = tree ? Math.max(tree.inaccessibleProcessCount, total - displayed) : 0;
          const isLive = pageMode === "live" && !state.frozen;

          return e("div", { class: "shell" },
            e("header", { class: "topbar" },
              e("div", null,
                e("p", { class: "eyebrow" }, "Harmon · process tree"),
                e("h1", null, "Self and descendant totals"),
                e("p", { class: "subtitle" }, "Every row is one PID. Total columns include the readable subtree.")
              ),
              e("button", {
                class: "mode-button",
                type: "button",
                disabled: pageMode !== "live",
                "data-live": String(isLive),
                onClick: toggleFreeze
              }, pageMode !== "live" ? "Saved snapshot" : isLive ? "● Live · Snapshot" : "Snapshot · Resume")
            ),
            e("section", { class: "summary-grid", "aria-label": "System summary" },
              summaryCard("Processes", String(displayed), total ? "of " + total : ""),
              summaryCard("CPU", system ? formatCpu(system.cpuTotalPercent) : "—", "system"),
              summaryCard("Physical memory", system ? formatBytes(system.physicalMemoryBytes) : "—", "installed"),
              summaryCard("Swap used", system ? formatBytes(system.swapUsedBytes) : "—", ""),
              summaryCard("Load", system ? finite(system.loadAverageOneMinute).toFixed(2) : "—", "1 minute"),
              summaryCard("Battery", system && system.batteryAvailable ? String(system.batteryPercentage ?? "—") + "%" : "n/a",
                system ? (system.charging ? "charging" : system.onBattery ? "on battery" : "AC power") : ""),
              summaryCard("Captured", captured, payload ? "sample " + payload.sequence : "")
            ),
            status ? e("p", {
              class: "notice" + (payload && payload.status === "STALE" ? " error" : ""),
              role: "status",
              "aria-live": "polite"
            }, status + (payload && payload.error ? ": " + payload.error : "")) : null,
            state.requestError ? e("p", { class: "notice error", role: "alert" }, "Live update failed: " + state.requestError) : null,
            payload && payload.alerts && payload.alerts.length ? e("p", { class: "notice" }, payload.alerts.map(alert => alert.title).join(" · ")) : null,
            e("section", { "aria-label": "Process table" },
              e("div", { class: "toolbar" },
                e("input", {
                  class: "search",
                  type: "search",
                  value: state.search,
                  placeholder: "Search process name or PID",
                  "aria-label": "Search processes",
                  onInput: event => { state.search = event.currentTarget.value; draw(); }
                }),
                e("span", { class: "toolbar-meta" }, unavailable ? unavailable + " unavailable · totals may be partial" : "all reported processes readable")
              ),
              e("div", { class: "table-wrap" },
                e("table", { role: "treegrid", "aria-label": "Processes" },
                  e("thead", null, e("tr", null,
                    sortHeader("PID", "pid", "pid"),
                    sortHeader("Process", "name", "process-column"),
                    sortHeader("CPU Self", "cpuSelfPercent", "metric-column"),
                    sortHeader("CPU Total", "cpuTotalPercent", "metric-column"),
                    sortHeader("Memory Self", "memorySelfBytes", "metric-column"),
                    sortHeader("Memory Total", "memoryTotalBytes", "metric-column")
                  )),
                  e("tbody", null,
                    tree && roots.length ? e(ProcessRows, { roots }) :
                      e("tr", null, e("td", { class: "empty", colSpan: 6 },
                        tree ? "No processes match this search." : "Waiting for process metrics…"
                      ))
                  )
                )
              )
            ),
            payload && payload.reportText ? e("details", { class: "report-details" },
              e("summary", null, "Full text report"),
              e("pre", null, payload.reportText)
            ) : null
          );
        }

        function draw() {
          render(e(App), root);
        }

        function schedule() {
          clearTimeout(state.timer);
          if (pageMode === "live" && !state.frozen) {
            const seconds = state.payload && (state.payload.retrySeconds || state.payload.sampleIntervalSeconds);
            state.timer = setTimeout(refresh, Math.max(250, finite(seconds || 1) * 1000));
          }
        }

        async function refresh() {
          if (pageMode !== "live" || state.frozen) return;
          const generation = state.pollGeneration;
          try {
            const response = await fetch("/api/live?token=" + encodeURIComponent(token), {
              cache: "no-store",
              headers: { "Accept": "application/json" }
            });
            if (!response.ok) throw new Error("HTTP " + response.status);
            const payload = await response.json();
            if (generation !== state.pollGeneration || state.frozen) return;
            state.payload = payload;
            state.requestError = null;
          } catch (error) {
            if (generation !== state.pollGeneration || state.frozen) return;
            state.requestError = error instanceof Error ? error.message : String(error);
          } finally {
            if (generation === state.pollGeneration) {
              draw();
              schedule();
            }
          }
        }

        function toggleFreeze() {
          if (pageMode !== "live") return;
          state.pollGeneration += 1;
          state.frozen = !state.frozen;
          if (state.frozen) clearTimeout(state.timer);
          draw();
          if (!state.frozen) refresh();
        }

        draw();
        if (pageMode === "live") refresh();
    """.trimIndent()
}
