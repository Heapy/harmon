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
              <main id="app"><p class="loading">Loading process metrics…</p></main>
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
          --pid-width: 78px;
          --process-width: 330px;
        }

        * { box-sizing: border-box; }
        body { margin: 0; min-width: 760px; background: #090c10; }
        button, input, select { font: inherit; }
        button:focus-visible, input:focus-visible, summary:focus-visible {
          outline: 2px solid var(--cyan); outline-offset: 2px;
        }

        .shell { min-height: 100vh; padding: 18px 22px 32px; }
        .topbar { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 14px; }
        .eyebrow {
          margin: 0 0 4px; color: var(--cyan);
          font: 700 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .13em; text-transform: uppercase;
        }
        h1 { margin: 0; color: #f5f7fa; font-size: 24px; letter-spacing: -.025em; }
        .subtitle { margin: 5px 0 0; color: var(--muted); font-size: 13px; }
        .mode-button, .preset-button {
          border: 1px solid #344255; border-radius: 7px; padding: 8px 12px;
          background: #17202a; color: #eff4fa; cursor: pointer;
        }
        .mode-button { min-width: 148px; }
        .mode-button[data-live="true"] { border-color: #267a58; color: var(--green); }
        .mode-button:disabled { cursor: default; opacity: .72; }

        .summary-grid {
          display: grid; grid-template-columns: repeat(5, minmax(118px, 1fr));
          gap: 8px; margin-bottom: 10px;
        }
        .summary-card {
          min-height: 65px; border: 1px solid var(--line); border-radius: 8px;
          padding: 9px 11px; background: linear-gradient(145deg, #121922, #0e131a);
        }
        .summary-label {
          display: block; margin-bottom: 6px; color: var(--muted);
          font: 700 10px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .08em; text-transform: uppercase;
        }
        .summary-value { color: #f3f6f9; font-size: 18px; font-weight: 650; }
        .summary-detail { margin-left: 5px; color: var(--muted); font-size: 11px; }

        .notice {
          margin: 0 0 10px; border: 1px solid #6f5125; border-radius: 7px;
          padding: 8px 10px; background: #211a10; color: #ffd897; font-size: 12px;
        }
        .notice.error { border-color: #713547; background: #25131a; color: #ffb0bf; }

        .toolbar {
          display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
          border: 1px solid var(--line); border-bottom: 0; border-radius: 9px 9px 0 0;
          padding: 8px; background: var(--panel);
        }
        .search {
          flex: 1 1 260px; min-width: 240px; border: 1px solid #354254;
          border-radius: 6px; padding: 7px 10px; background: #0b1016; color: #edf2f7;
        }
        .search::placeholder { color: #66758a; }
        .preset-list { display: flex; gap: 5px; }
        .preset-button { padding: 6px 8px; color: #aeb9c7; font-size: 11px; }
        .preset-button.active { border-color: #34758a; color: var(--cyan); background: #12232c; }
        .column-picker { position: relative; }
        .column-picker > summary {
          border: 1px solid #344255; border-radius: 6px; padding: 6px 9px;
          color: #d5dde7; cursor: pointer; font-size: 11px; list-style: none;
        }
        .column-picker > summary::-webkit-details-marker { display: none; }
        .column-menu {
          position: absolute; right: 0; z-index: 20; display: grid; grid-template-columns: repeat(2, 190px);
          gap: 4px 10px; margin-top: 6px; border: 1px solid #3a4758; border-radius: 8px;
          padding: 10px; background: #121922; box-shadow: 0 12px 30px #000b;
        }
        .column-menu label { display: flex; gap: 7px; align-items: center; color: #c0cad6; font-size: 11px; }
        .toolbar-meta {
          margin-left: auto; white-space: nowrap; color: var(--muted);
          font: 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        .table-wrap {
          max-height: 68vh; overflow: auto; border: 1px solid var(--line);
          border-radius: 0 0 9px 9px; background: #0c1117;
        }
        table { min-width: 100%; border-collapse: separate; border-spacing: 0; table-layout: fixed; }
        thead { position: sticky; top: 0; z-index: 8; }
        th {
          border-bottom: 1px solid #334052; padding: 0; background: #171e27;
          color: #9eacbd; text-align: right;
          font: 700 10px/1.1 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .04em; text-transform: uppercase;
        }
        .sort-button {
          width: 100%; border: 0; padding: 9px 8px; background: transparent;
          color: inherit; text-align: inherit; cursor: pointer;
        }
        td {
          position: relative; height: 34px; border-bottom: 1px solid #18212b;
          padding: 0 9px; overflow: hidden; background: #0c1117; color: #cad2dd;
          text-align: right; text-overflow: ellipsis; white-space: nowrap;
          font: 12px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
        }
        tbody tr:hover td { background-color: #121a24; }
        tbody tr.unavailable td { color: #b79c6e; }
        .pid-column, .pid-cell {
          position: sticky; left: 0; width: var(--pid-width); min-width: var(--pid-width);
          max-width: var(--pid-width); z-index: 5; text-align: left;
        }
        .process-column, .process-cell {
          position: sticky; left: var(--pid-width); width: var(--process-width);
          min-width: var(--process-width); max-width: var(--process-width); z-index: 5; text-align: left;
          box-shadow: 1px 0 #334052;
        }
        thead .pid-column, thead .process-column { z-index: 12; background: #171e27; }
        .metric-column { width: 128px; min-width: 128px; }
        .tree-cell { display: flex; align-items: center; min-width: 0; height: 34px; }
        .tree-guide { flex: none; width: calc(var(--depth) * 18px); }
        .twisty, .twisty-space { flex: none; width: 24px; height: 26px; margin-right: 2px; }
        .twisty {
          border: 0; border-radius: 4px; background: transparent; color: #8191a6; cursor: pointer;
        }
        .twisty:hover { background: #24303e; color: #dbe5ef; }
        .process-name { overflow: hidden; text-overflow: ellipsis; color: #eef2f6; }
        .partial { margin-left: 6px; color: var(--amber); font-size: 9px; text-transform: uppercase; }
        .issue { margin-left: 7px; color: #b79c6e; font-size: 10px; text-transform: lowercase; }
        .self { color: #aab5c2; }
        .total { color: #e5fbff; font-weight: 700; }
        .not-available { color: #647286; }
        .empty { padding: 42px 20px; color: var(--muted); text-align: center; }
        .loading { padding: 40px; color: var(--muted); }
        .raw-report { padding: 24px; }
        .raw-report pre { white-space: pre-wrap; font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }

        .system-details, .report-details {
          margin-top: 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel);
        }
        .system-details summary, .report-details summary {
          padding: 10px 12px; color: var(--muted); cursor: pointer;
          font: 700 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
          letter-spacing: .05em; text-transform: uppercase;
        }
        .details-grid {
          display: grid; grid-template-columns: repeat(4, minmax(190px, 1fr));
          gap: 8px; padding: 0 12px 14px;
        }
        .details-group { border: 1px solid #222d39; border-radius: 7px; padding: 9px; background: #0d131a; }
        .details-group h3 { margin: 0 0 7px; color: var(--cyan); font-size: 11px; text-transform: uppercase; }
        .detail-row { display: flex; justify-content: space-between; gap: 12px; padding: 2px 0; color: #9eabba; font-size: 11px; }
        .detail-row span:last-child { color: #d7dee7; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .report-details pre {
          max-height: 420px; margin: 0; padding: 0 12px 14px; overflow: auto;
          white-space: pre-wrap; color: #bec8d4;
          font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        @media (max-width: 1100px) {
          .summary-grid { grid-template-columns: repeat(3, 1fr); }
          .details-grid { grid-template-columns: repeat(2, minmax(190px, 1fr)); }
        }
        @media (prefers-reduced-motion: no-preference) {
          .mode-button, .twisty, tbody td { transition: background-color 120ms, color 120ms; }
        }
    """.trimIndent()

    private val APP = $$"""
        const { h, render } = globalThis.preact;
        const root = document.getElementById("app");
        const bootstrapNode = document.getElementById("harmon-bootstrap");
        const pageMode = document.body.dataset.mode || "live";
        const tokenPattern = /^[0-9a-f]{64}$/;
        const tokenStorageKey = "harmon.liveUiToken";
        let token = "";
        let bootstrapError = null;
        if (pageMode === "live") {
          try {
            if (location.hash) {
              const fragmentTokens = new URLSearchParams(location.hash.slice(1)).getAll("token");
              if (fragmentTokens.length !== 1 || !tokenPattern.test(fragmentTokens[0])) {
                throw new Error("invalid live UI token");
              }
              sessionStorage.setItem(tokenStorageKey, fragmentTokens[0]);
              history.replaceState(null, "", "/");
              token = fragmentTokens[0];
            } else {
              const storedToken = sessionStorage.getItem(tokenStorageKey) || "";
              if (!tokenPattern.test(storedToken)) throw new Error("missing live UI token");
              token = storedToken;
            }
          } catch (_) {
            token = "";
            bootstrapError = "run harmon ui again";
          }
        }
        const e = (type, props, ...children) => h(type, props, ...children);
        const initial = JSON.parse(bootstrapNode.textContent || "null");

        const columns = [
          { id: "cpu", group: "Overview", label: "CPU", metric: "cpuPercent", format: "percent", kind: "decimal" },
          { id: "userCpu", group: "Overview", label: "User CPU", metric: "userCpuPercent", format: "percent", kind: "decimal" },
          { id: "systemCpu", group: "Overview", label: "System CPU", metric: "systemCpuPercent", format: "percent", kind: "decimal" },
          { id: "footprint", group: "Overview", label: "Footprint", metric: "physicalFootprintBytes", format: "bytes", kind: "integer" },
          { id: "resident", group: "Memory", label: "Resident", metric: "residentBytes", format: "bytes", kind: "integer" },
          { id: "wired", group: "Memory", label: "Wired", metric: "wiredBytes", format: "bytes", kind: "integer" },
          { id: "compressed", group: "Memory", label: "Compressed / paged", metric: "compressedOrPagedOutBytes", format: "bytes", kind: "integer" },
          { id: "regions", group: "Memory", label: "VM regions", metric: "virtualMemoryRegionCount", format: "integer", kind: "integer" },
          { id: "peak", group: "Memory", label: "Lifetime peak", metric: "lifetimeMaxPhysicalFootprintBytes", format: "bytes", kind: "integer", selfOnly: true },
          { id: "diskRead", group: "I/O", label: "Disk read", metric: "diskReadBytesPerSecond", format: "bytesRate", kind: "decimal" },
          { id: "diskWrite", group: "I/O", label: "Disk write", metric: "diskWriteBytesPerSecond", format: "bytesRate", kind: "decimal" },
          { id: "logicalWrite", group: "I/O", label: "Logical writes", metric: "logicalWriteBytesPerSecond", format: "bytesRate", kind: "decimal" },
          { id: "pageIns", group: "I/O", label: "Page-ins", metric: "pageInsPerSecond", format: "rate", kind: "decimal" },
          { id: "wakeups", group: "Activity", label: "Wakeups", metric: "wakeupsPerSecond", format: "rate", kind: "decimal" },
          { id: "faults", group: "Activity", label: "Faults", metric: "faultsPerSecond", format: "rate", kind: "decimal" },
          { id: "cowFaults", group: "Activity", label: "CoW faults", metric: "copyOnWriteFaultsPerSecond", format: "rate", kind: "decimal" },
          { id: "syscalls", group: "Activity", label: "Syscalls", metric: "systemCallsPerSecond", format: "rate", kind: "decimal" },
          { id: "switches", group: "Activity", label: "Context switches", metric: "contextSwitchesPerSecond", format: "rate", kind: "decimal" },
          { id: "threads", group: "Activity", label: "Threads", metric: "threadCount", format: "integer", kind: "integer" },
          { id: "runningThreads", group: "Activity", label: "Running threads", metric: "runningThreadCount", format: "integer", kind: "integer" },
          { id: "instructions", group: "Compute / Energy", label: "Instructions", metric: "instructionsPerSecond", format: "rate", kind: "decimal" },
          { id: "cycles", group: "Compute / Energy", label: "Cycles", metric: "cyclesPerSecond", format: "rate", kind: "decimal" },
          { id: "watts", group: "Compute / Energy", label: "Watts", metric: "energyWatts", format: "watts", kind: "decimal" },
          { id: "impact", group: "Compute / Energy", label: "Battery impact", metric: "batteryImpactScore", format: "score", kind: "decimal" }
        ];
        const columnById = Object.fromEntries(columns.map(column => [column.id, column]));
        const presets = Object.fromEntries(
          ["Overview", "Memory", "I/O", "Activity", "Compute / Energy"].map(group =>
            [group, columns.filter(column => column.group === group).map(column => column.id)])
        );

        const state = {
          payload: initial,
          frozen: pageMode !== "live",
          search: "",
          sort: { column: "cpu", scope: "total" },
          sortDirection: "desc",
          selectedColumns: new Set(presets.Overview),
          expanded: new Set(),
          initializedExpansion: false,
          requestError: bootstrapError,
          timer: null,
          pollGeneration: 0
        };

        function asBigInt(value) {
          try { return BigInt(value || "0"); } catch (_) { return 0n; }
        }

        function asNumber(value) {
          const number = Number(value);
          return Number.isFinite(number) && number >= 0 ? number : 0;
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
          let tenths = (bytes * 10n + divisor / 2n) / divisor;
          while (unit < units.length - 1 && tenths >= 10240n) {
            divisor *= 1024n;
            unit += 1;
            tenths = (bytes * 10n + divisor / 2n) / divisor;
          }
          return (tenths / 10n).toString() + "." + (tenths % 10n).toString() + " " + units[unit];
        }

        function formatRate(value) {
          const number = asNumber(value);
          if (number >= 1000000000) return (number / 1000000000).toFixed(1) + "G/s";
          if (number >= 1000000) return (number / 1000000).toFixed(1) + "M/s";
          if (number >= 1000) return (number / 1000).toFixed(1) + "k/s";
          return number.toFixed(number >= 100 ? 0 : 1) + "/s";
        }

        function formatMetric(column, value) {
          if (column.format === "bytes") return formatBytes(value);
          if (column.format === "bytesRate") return formatBytes(String(Math.round(asNumber(value)))) + "/s";
          if (column.format === "integer") return asBigInt(value).toString();
          if (column.format === "percent") {
            const number = asNumber(value);
            return number >= 100 ? number.toFixed(0) + "%" : number.toFixed(1) + "%";
          }
          if (column.format === "watts") return asNumber(value).toFixed(3) + " W";
          if (column.format === "score") return asNumber(value).toFixed(1);
          return formatRate(value);
        }

        function metric(node, column) {
          return node.metrics && node.metrics[column.metric];
        }

        function metricAvailable(value, scope) {
          return Boolean(value && value[scope + "Available"]);
        }

        function compareMetric(leftNode, rightNode, column, scope) {
          const left = metric(leftNode, column);
          const right = metric(rightNode, column);
          const leftAvailable = metricAvailable(left, scope);
          const rightAvailable = metricAvailable(right, scope);
          if (leftAvailable !== rightAvailable) return leftAvailable ? -1 : 1;
          if (!leftAvailable) return 0;
          const leftValue = left[scope];
          const rightValue = right[scope];
          let compared;
          if (column.kind === "integer") {
            const a = asBigInt(leftValue);
            const b = asBigInt(rightValue);
            compared = a < b ? -1 : a > b ? 1 : 0;
          } else {
            compared = asNumber(leftValue) - asNumber(rightValue);
          }
          return state.sortDirection === "desc" ? -compared : compared;
        }

        function nodeComparator(a, b) {
          let compared = 0;
          if (state.sort.column === "pid") {
            compared = a.pid - b.pid;
            if (state.sortDirection === "desc") compared = -compared;
          } else if (state.sort.column === "name") {
            compared = a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
            if (state.sortDirection === "desc") compared = -compared;
          } else {
            const column = columnById[state.sort.column];
            if (column) compared = compareMetric(a, b, column, state.sort.scope);
          }
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
          const children = (node.children || []).map(child => filterNode(child, query, false)).filter(Boolean);
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

        function setSort(column, scope) {
          if (state.sort.column === column && state.sort.scope === scope) {
            state.sortDirection = state.sortDirection === "desc" ? "asc" : "desc";
          } else {
            state.sort = { column, scope };
            state.sortDirection = column === "name" || column === "pid" ? "asc" : "desc";
          }
          draw();
        }

        function sortHeader(label, column, scope, className) {
          const active = state.sort.column === column && state.sort.scope === scope;
          const suffix = active ? (state.sortDirection === "desc" ? " ▼" : " ▲") : "";
          return e("th", {
            class: className,
            "aria-sort": active ? (state.sortDirection === "desc" ? "descending" : "ascending") : "none"
          }, e("button", {
            class: "sort-button",
            type: "button",
            "aria-label": "Sort by " + label,
            onClick: () => setSort(column, scope)
          }, label + suffix));
        }

        function selectedMetricColumns() {
          const result = [];
          for (const column of columns) {
            if (!state.selectedColumns.has(column.id)) continue;
            result.push({ column, scope: "self" });
            if (!column.selfOnly) result.push({ column, scope: "total" });
          }
          return result;
        }

        function metricCell(node, column, scope) {
          const value = metric(node, column);
          const available = metricAvailable(value, scope);
          const partial = scope === "total" && value && value.totalPartial;
          return e("td", { class: "metric-column " + scope },
            available ? formatMetric(column, value[scope]) : e("span", { class: "not-available" }, "—"),
            partial ? e("span", {
              class: "partial",
              title: "Known subtotal; unavailable values are excluded"
            }, "partial") : null
          );
        }

        function flatten(nodes, depth, forceOpen, rows) {
          for (const node of nodes) {
            rows.push({ node, depth });
            if (forceOpen || state.expanded.has(node.key)) {
              flatten(node.children || [], depth + 1, forceOpen, rows);
            }
          }
          return rows;
        }

        function ProcessRows({ roots, metricColumns }) {
          const forceOpen = state.search.trim().length > 0;
          const rows = flatten(roots, 0, forceOpen, []);
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
              e("td", { class: "pid-cell" }, String(node.pid)),
              e("td", { class: "process-cell" },
                e("div", { class: "tree-cell", style: { "--depth": String(depth) } },
                  e("span", { class: "tree-guide" }),
                  hasChildren ? e("button", {
                    class: "twisty", type: "button", "aria-label": toggleLabel,
                    "aria-expanded": String(open),
                    onClick: () => {
                      if (state.expanded.has(node.key)) state.expanded.delete(node.key);
                      else state.expanded.add(node.key);
                      draw();
                    }
                  }, open ? "▾" : "▸") : e("span", { class: "twisty-space" }),
                  e("span", { class: "process-name", title: node.executablePath || node.name }, node.name),
                  !node.measured ? e("span", { class: "issue" },
                    String(node.issueReason || "unavailable").replaceAll("_", " ")) : null
                )
              ),
              ...metricColumns.map(({ column, scope }) => metricCell(node, column, scope))
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
          if (payload.status === "WARMING") return "Collecting a fresh baseline; the previous tree remains visible";
          return null;
        }

        function attributionSummary(payload) {
          const unavailable = {
            captured: "—",
            detail: "unavailable",
            text: "Last FULL attribution: unavailable"
          };
          if (!payload || !payload.system || !payload.attributionCapturedAt) return unavailable;
          const capturedDate = new Date(payload.attributionCapturedAt);
          const capturedMillis = capturedDate.getTime();
          if (!Number.isFinite(capturedMillis)) return unavailable;
          const processes = payload.system.processes;
          const measured = Math.floor(asNumber(processes.compressedAttributionAvailable));
          const failed = Math.floor(asNumber(processes.compressedAttributionFailures));
          const dynamicAge = Math.max(0, (Date.now() - capturedMillis) / 1000);
          const age = Math.floor(Math.max(asNumber(payload.attributionAgeSeconds), dynamicAge));
          return {
            captured: capturedDate.toLocaleTimeString(),
            detail: measured + " measured · " + failed + " attempts failed · " + age + "s old",
            text: "Last FULL attribution: " + measured + " measured, " + failed +
              " attempts failed · captured " + payload.attributionCapturedAt + " · " + age + "s old"
          };
        }

        function detailRow(label, value) {
          return e("div", { class: "detail-row" }, e("span", null, label), e("span", null, value));
        }

        function detailsGroup(title, rows) {
          return e("section", { class: "details-group" }, e("h3", null, title), ...rows);
        }

        function SystemDetails({ system, attribution }) {
          if (!system) return null;
          const vm = system.virtualMemory;
          const storage = system.storage;
          const power = system.power;
          const load = system.load;
          const processor = system.processor;
          return e("details", { class: "system-details" },
            e("summary", null, "System details"),
            e("div", { class: "details-grid" },
              detailsGroup("Processor / load", [
                detailRow("CPU total", processor.totalPercent.toFixed(1) + "%"),
                detailRow("User / system", processor.userPercent.toFixed(1) + "% / " + processor.systemPercent.toFixed(1) + "%"),
                detailRow("Nice / idle", processor.nicePercent.toFixed(1) + "% / " + processor.idlePercent.toFixed(1) + "%"),
                detailRow("Load 1 / 5 / 15m", load.oneMinute.toFixed(2) + " / " + load.fiveMinutes.toFixed(2) + " / " + load.fifteenMinutes.toFixed(2))
              ]),
              detailsGroup("Virtual memory", [
                detailRow("Free / active", formatBytes(vm.freeBytes) + " / " + formatBytes(vm.activeBytes)),
                detailRow("Inactive / wired", formatBytes(vm.inactiveBytes) + " / " + formatBytes(vm.wiredBytes)),
                detailRow("Purgeable / compressed", formatBytes(vm.purgeableBytes) + " / " + formatBytes(vm.compressedBytes)),
                detailRow("Compressor / swap-backed", formatBytes(vm.uncompressedBytesInCompressor) + " / " + formatBytes(vm.swapBackedUncompressedBytes)),
                detailRow("Page in / out", formatBytes(String(Math.round(vm.pageInBytesPerSecond))) + "/s / " + formatBytes(String(Math.round(vm.pageOutBytesPerSecond))) + "/s"),
                detailRow("Faults / CoW", formatRate(vm.faultRate) + " / " + formatRate(vm.copyOnWriteFaultRate)),
                detailRow("Compress / decompress", formatBytes(String(Math.round(vm.compressionBytesPerSecond))) + "/s / " + formatBytes(String(Math.round(vm.decompressionBytesPerSecond))) + "/s"),
                detailRow("Swap in / out", formatBytes(String(Math.round(vm.swapInBytesPerSecond))) + "/s / " + formatBytes(String(Math.round(vm.swapOutBytesPerSecond))) + "/s")
              ]),
              detailsGroup("Storage / swap", [
                detailRow("Storage", storage.available ? storage.deviceCount + " device(s)" : "unavailable"),
                detailRow("Read / write", formatBytes(String(Math.round(storage.readBytesPerSecond))) + "/s / " + formatBytes(String(Math.round(storage.writeBytesPerSecond))) + "/s"),
                detailRow("Read / write ops", formatRate(storage.readOperationsPerSecond) + " / " + formatRate(storage.writeOperationsPerSecond)),
                detailRow("Service time", storage.readServiceTimePercent.toFixed(1) + "% / " + storage.writeServiceTimePercent.toFixed(1) + "%"),
                detailRow("Root free / total", formatBytes(storage.rootFileSystemAvailableBytes) + " / " + formatBytes(storage.rootFileSystemTotalBytes)),
                detailRow("Swap used / total", formatBytes(system.swap.usedBytes) + " / " + formatBytes(system.swap.totalBytes)),
                detailRow("Swap available", formatBytes(system.swap.availableBytes) + (system.swap.encrypted ? " · encrypted" : ""))
              ]),
              detailsGroup("Power / collection", [
                detailRow("Power source", !power.batteryAvailable ? "No battery" : power.charging ? "Charging" : power.onBattery ? "Battery" : "AC"),
                detailRow("Battery", power.batteryAvailable ? String(power.percentage ?? "—") + "%" : "n/a"),
                detailRow("Time remaining", power.minutesRemaining == null ? "—" : power.minutesRemaining + " min"),
                detailRow("Processes", system.processes.total + " total · " + system.processes.inaccessible + " inaccessible"),
                detailRow("Attribution", attribution.text),
                detailRow("Energy counter", system.energyAccounted ? "accounted" : "fallback score")
              ])
            )
          );
        }

        function setPreset(name) {
          state.selectedColumns = new Set(presets[name]);
          if (state.sort.scope !== "identity" && !state.selectedColumns.has(state.sort.column)) {
            const first = presets[name][0];
            state.sort = { column: first, scope: columnById[first].selfOnly ? "self" : "total" };
            state.sortDirection = "desc";
          }
          draw();
        }

        function activePreset(name) {
          const ids = presets[name];
          return ids.length === state.selectedColumns.size && ids.every(id => state.selectedColumns.has(id));
        }

        function toggleColumn(id, checked) {
          if (checked) state.selectedColumns.add(id); else state.selectedColumns.delete(id);
          if (!state.selectedColumns.size) state.selectedColumns.add("cpu");
          if (state.sort.scope !== "identity" && !state.selectedColumns.has(state.sort.column)) {
            const first = [...state.selectedColumns][0];
            state.sort = { column: first, scope: columnById[first].selfOnly ? "self" : "total" };
            state.sortDirection = "desc";
          }
          draw();
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
          const unavailable = tree ? Math.max(tree.inaccessibleProcessCount, total - tree.measuredProcessCount) : 0;
          const isLive = pageMode === "live" && !state.frozen && document.visibilityState === "visible";
          const metricColumns = selectedMetricColumns();
          const attribution = attributionSummary(payload);

          return e("div", { class: "shell" },
            e("header", { class: "topbar" },
              e("div", null,
                e("p", { class: "eyebrow" }, "Harmon · process tree"),
                e("h1", null, "Self and descendant totals"),
                e("p", { class: "subtitle" }, "Every row is one PID. Known partial totals remain sortable.")
              ),
              e("button", {
                class: "mode-button", type: "button", disabled: pageMode !== "live",
                "data-live": String(isLive), onClick: toggleFreeze
              }, pageMode !== "live" ? "Saved snapshot" : state.frozen ? "Snapshot · Resume" : isLive ? "● Live · Snapshot" : "Live · hidden")
            ),
            e("section", { class: "summary-grid", "aria-label": "System summary" },
              summaryCard("Processes", String(displayed), total ? "of " + total : ""),
              summaryCard("CPU", system ? system.processor.totalPercent.toFixed(1) + "%" : "—", "system"),
              summaryCard("Physical memory", system ? formatBytes(system.physicalMemoryBytes) : "—", "installed"),
              summaryCard("Swap used", system ? formatBytes(system.swap.usedBytes) : "—", ""),
              summaryCard("Load", system ? system.load.oneMinute.toFixed(2) : "—", "1 minute"),
              summaryCard("Battery", system && system.power.batteryAvailable ? String(system.power.percentage ?? "—") + "%" : "n/a",
                system ? (system.power.charging ? "charging" : system.power.onBattery ? "on battery" : "AC power") : ""),
              summaryCard("Captured", captured, payload ? "sample " + payload.sequence : ""),
              summaryCard("Last FULL attribution", attribution.captured, attribution.detail)
            ),
            status ? e("p", {
              class: "notice" + (payload && payload.status === "STALE" ? " error" : ""),
              role: "status", "aria-live": "polite"
            }, status + (payload && payload.error ? ": " + payload.error : "")) : null,
            payload && payload.attributionWarning ? e("p", { class: "notice", role: "status" }, "Attribution warning: " + payload.attributionWarning) : null,
            state.requestError ? e("p", { class: "notice error", role: "alert" }, "Live update failed: " + state.requestError) : null,
            payload && payload.alerts && payload.alerts.length ? e("p", { class: "notice" }, payload.alerts.map(alert => alert.title).join(" · ")) : null,
            e("section", { "aria-label": "Process table" },
              e("div", { class: "toolbar" },
                e("input", {
                  class: "search", type: "search", value: state.search,
                  placeholder: "Search process name or PID", "aria-label": "Search processes",
                  onInput: event => { state.search = event.currentTarget.value; draw(); }
                }),
                e("div", { class: "preset-list", "aria-label": "Column presets" },
                  ...Object.keys(presets).map(name => e("button", {
                    class: "preset-button" + (activePreset(name) ? " active" : ""),
                    type: "button", onClick: () => setPreset(name)
                  }, name))
                ),
                e("details", { class: "column-picker" },
                  e("summary", null, "Columns (" + state.selectedColumns.size + ")"),
                  e("div", { class: "column-menu" }, ...columns.map(column =>
                    e("label", { key: column.id },
                      e("input", {
                        type: "checkbox", checked: state.selectedColumns.has(column.id),
                        onChange: event => toggleColumn(column.id, event.currentTarget.checked)
                      }),
                      column.group + " · " + column.label
                    )
                  ))
                ),
                e("span", { class: "toolbar-meta" }, unavailable ? unavailable + " unavailable process(es)" : "all reported processes readable")
              ),
              e("div", { class: "table-wrap" },
                e("table", { role: "treegrid", "aria-label": "Processes" },
                  e("thead", null, e("tr", null,
                    sortHeader("PID", "pid", "identity", "pid-column"),
                    sortHeader("Process", "name", "identity", "process-column"),
                    ...metricColumns.map(({ column, scope }) =>
                      sortHeader(column.label + " " + (scope === "self" ? "Self" : "Total"), column.id, scope, "metric-column")
                    )
                  )),
                  e("tbody", null,
                    tree && roots.length ? e(ProcessRows, { roots, metricColumns }) :
                      e("tr", null, e("td", { class: "empty", colSpan: 2 + metricColumns.length },
                        tree ? "No processes match this search." : "Waiting for process metrics…"
                      ))
                  )
                )
              )
            ),
            e(SystemDetails, { system, attribution }),
            payload && payload.reportText ? e("details", { class: "report-details" },
              e("summary", null, "Full text report"), e("pre", null, payload.reportText)
            ) : null
          );
        }

        function draw() { render(e(App), root); }

        function canPoll() {
          return pageMode === "live" && tokenPattern.test(token) &&
            !state.frozen && document.visibilityState === "visible";
        }

        function schedule() {
          clearTimeout(state.timer);
          if (canPoll()) {
            const seconds = state.payload && (state.payload.retrySeconds || state.payload.sampleIntervalSeconds);
            state.timer = setTimeout(refresh, Math.max(250, asNumber(seconds || 1) * 1000));
          }
        }

        async function refresh() {
          if (!canPoll()) return;
          const generation = state.pollGeneration;
          try {
            const response = await fetch("/api/live?watch=1", {
              cache: "no-store",
              headers: { "Accept": "application/json", "Authorization": "Bearer " + token }
            });
            if (response.status === 401 || response.status === 403) {
              throw new Error("run harmon ui again");
            }
            if (!response.ok) throw new Error("HTTP " + response.status);
            const payload = await response.json();
            if (payload.schemaVersion !== 2) throw new Error("unsupported schema " + payload.schemaVersion);
            if (generation !== state.pollGeneration || !canPoll()) return;
            state.payload = payload;
            state.requestError = null;
          } catch (error) {
            if (generation !== state.pollGeneration || !canPoll()) return;
            state.requestError = error instanceof Error ? error.message : String(error);
          } finally {
            if (generation === state.pollGeneration) { draw(); schedule(); }
          }
        }

        function toggleFreeze() {
          if (pageMode !== "live") return;
          state.pollGeneration += 1;
          state.frozen = !state.frozen;
          clearTimeout(state.timer);
          draw();
          if (canPoll()) refresh();
        }

        document.addEventListener("visibilitychange", () => {
          state.pollGeneration += 1;
          clearTimeout(state.timer);
          draw();
          if (canPoll()) refresh();
        });

        root.replaceChildren();
        draw();
        if (canPoll()) refresh();
    """.trimIndent()
}
