package dev.yoda.harmon.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessTreeUiTest {
    @Test
    fun defaultsToOverviewAndRecursiveCpuTotalsWithPinnedIdentityColumns() =
        processUiOnLivePage("overview-totals") { _, page ->
            val rows = page.locator("tbody tr")
            assertThat(rows.first()).hasAttribute("data-pid", "200")
            assertThat(rows.nth(1)).hasAttribute("data-pid", "100")

            val firefox = processUiRow(page, 100)
            assertThat(firefox.locator("td").nth(0)).hasText("100")
            assertThat(firefox.locator("td").nth(2)).hasText("5.0%")
            assertThat(firefox.locator("td").nth(3)).hasText("50.0%")
            assertThat(firefox.locator("td").nth(8)).hasText("1.0 GiB")
            assertThat(firefox.locator("td").nth(9)).hasText("10.0 GiB")
            assertThat(processUiSortHeader(page, "CPU Total").locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")
            assertThat(page.getByRole(AriaRole.BUTTON, processUiNamed("Overview")))
                .hasClass("preset-button active")
            assertThat(page.locator("th.pid-column")).hasCSS("position", "sticky")
            assertThat(page.locator("th.process-column")).hasCSS("position", "sticky")

            assertThat(processUiRow(page, 103)).isVisible()
            assertThat(processUiRow(page, 300)).containsText("partial")
        }

    @Test
    fun selfAndTotalSortIndependentlyInsideEverySiblingSet() =
        processUiOnLivePage("independent-sorting") { _, page ->
            processUiSortHeader(page, "CPU Self").click()
            processUiAssertPids(page, listOf("200", "100", "102", "101", "103", "300", "301"))

            processUiSortHeader(page, "CPU Total").click()
            processUiAssertPids(page, listOf("200", "100", "101", "103", "102", "300", "301"))
        }

    @Test
    fun unavailableValuesStayAtTheBottomButKnownPartialTotalsStillSort() =
        processUiOnLivePage("availability-sorting") { _, page ->
            processUiSortHeader(page, "CPU Self").click()
            processUiSortHeader(page, "CPU Self").click()
            val ascendingRoots = processUiRootPids(page)
            assertEquals(listOf("100", "200", "300"), ascendingRoots)

            processUiSortHeader(page, "CPU Total").click()
            processUiSortHeader(page, "CPU Total").click()
            val totalAscendingRoots = processUiRootPids(page)
            assertEquals(listOf("300", "100", "200"), totalAscendingRoots)
            assertThat(processUiRow(page, 300)).containsText("partial")
        }

    @Test
    fun pidSortPersistsAcrossColumnsPollSnapshotWarmingAndResume() =
        processUiAssertIdentitySortPersistence(
            label = "PID",
            ascending = listOf("100", "101", "103", "102", "200", "300", "301"),
            descending = listOf("300", "301", "200", "100", "102", "101", "103"),
            descendingAfterPoll = listOf(
                "300",
                "301",
                "200",
                "100",
                "104",
                "102",
                "101",
                "103",
            ),
        )

    @Test
    fun processSortPersistsAcrossColumnsPollSnapshotWarmingAndResume() =
        processUiAssertIdentitySortPersistence(
            label = "Process",
            ascending = listOf("200", "100", "101", "103", "102", "300", "301"),
            descending = listOf("300", "301", "100", "102", "101", "103", "200"),
            descendingAfterPoll = listOf(
                "300",
                "301",
                "100",
                "104",
                "102",
                "101",
                "103",
                "200",
            ),
        )

    @Test
    fun byteFormattingPromotesRoundedValuesThroughTheHighestSupportedUnit() =
        processUiOnLivePage("byte-unit-promotion") { _, page ->
            val cases = listOf(
                "1023" to "1023 B",
                "1024" to "1.0 KiB",
                "1048575" to "1.0 MiB",
                "1073741823" to "1.0 GiB",
                "1099511627775" to "1.0 TiB",
                "1125899906842623" to "1.0 PiB",
                "1152921504606846975" to "1.0 EiB",
                "18446744073709551615" to "16.0 EiB",
            )
            val formatted = page.evaluate(
                "values => values.map(value => formatBytes(value))",
                cases.map { it.first },
            ) as List<*>

            assertEquals(cases.map { it.second }, formatted)
        }

    @Test
    fun presetsExposeEveryMetricGroupAndLifetimePeakIsSelfOnly() =
        processUiOnLivePage("column-presets") { _, page ->
            page.getByRole(AriaRole.BUTTON, processUiNamed("Memory")).click()
            assertThat(processUiSortHeader(page, "Resident Self")).isVisible()
            assertThat(processUiSortHeader(page, "Compressed / paged Total")).isVisible()
            assertThat(processUiSortHeader(page, "VM regions Total")).isVisible()
            assertThat(processUiSortHeader(page, "Lifetime peak Self")).isVisible()
            assertThat(
                page.getByRole(
                    AriaRole.BUTTON,
                    processUiNamed("Sort by Lifetime peak Total"),
                ),
            )
                .hasCount(0)

            page.getByRole(AriaRole.BUTTON, processUiNamed("I/O")).click()
            assertThat(processUiSortHeader(page, "Disk read Total")).isVisible()
            assertThat(processUiSortHeader(page, "Logical writes Self")).isVisible()
            assertThat(processUiSortHeader(page, "Page-ins Total")).isVisible()

            page.getByRole(AriaRole.BUTTON, processUiNamed("Activity")).click()
            assertThat(processUiSortHeader(page, "Wakeups Total")).isVisible()
            assertThat(processUiSortHeader(page, "Syscalls Self")).isVisible()
            assertThat(processUiSortHeader(page, "Threads Total")).isVisible()

            page.getByRole(AriaRole.BUTTON, processUiNamed("Compute / Energy")).click()
            assertThat(processUiSortHeader(page, "Instructions Total")).isVisible()
            assertThat(processUiSortHeader(page, "Cycles Self")).isVisible()
            assertThat(processUiSortHeader(page, "Watts Total")).isVisible()
            assertThat(processUiSortHeader(page, "Battery impact Total")).isVisible()
        }

    @Test
    fun searchKeepsAncestorsAndTheMatchedSubtree() = processUiOnLivePage("search") { _, page ->
        val search = page.getByRole(AriaRole.SEARCHBOX, processUiNamed("Search processes"))
        search.fill("103")
        processUiAssertPids(page, listOf("100", "101", "103"))

        search.fill("Firefox")
        processUiAssertPids(page, listOf("100", "101", "103", "102"))
        assertThat(processUiRow(page, 200)).hasCount(0)
    }

    @Test
    fun treeControlsCollapseAndExpandByProcess() = processUiOnLivePage("tree-controls") { _, page ->
        page.getByRole(AriaRole.BUTTON, processUiNamed("Collapse Firefox (PID 100)")).click()
        assertThat(processUiRow(page, 101)).hasCount(0)

        page.getByRole(AriaRole.BUTTON, processUiNamed("Expand Firefox (PID 100)")).click()
        assertThat(processUiRow(page, 101)).isVisible()
    }

    @Test
    fun selectionsSortSearchAndExpansionSurvivePollWarmingSnapshotAndResume() =
        processUiOnLivePage("persistent-state") { harness, page ->
            page.getByRole(AriaRole.BUTTON, processUiNamed("Memory")).click()
            processUiSortHeader(page, "Resident Self").click()
            page.getByRole(AriaRole.BUTTON, processUiNamed("Collapse Firefox (PID 100)")).click()
            val search = page.getByRole(AriaRole.SEARCHBOX, processUiNamed("Search processes"))
            search.fill("Firefox")

            harness.sendAndWait("sample 2")
            assertThat(processUiRow(page, 100).locator("td").nth(2)).hasText("2.0 GiB")
            assertThat(search).hasValue("Firefox")
            assertThat(page.getByRole(AriaRole.BUTTON, processUiNamed("Memory")))
                .hasClass("preset-button active")
            assertThat(processUiSortHeader(page, "Resident Self").locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")
            assertThat(processUiRow(page, 104)).isVisible()

            search.fill("")
            assertThat(processUiRow(page, 101)).hasCount(0)

            val mode = page.locator(".mode-button")
            mode.click()
            harness.sendAndWait("warming")
            page.waitForTimeout(1_250.0)
            assertThat(mode).containsText("Snapshot · Resume")
            assertThat(processUiRow(page, 100).locator("td").nth(2)).hasText("2.0 GiB")

            mode.click()
            assertThat(page.locator(".notice").first()).containsText("fresh baseline")
            assertThat(page.getByRole(AriaRole.BUTTON, processUiNamed("Memory")))
                .hasClass("preset-button active")
            assertThat(processUiRow(page, 101)).hasCount(0)
        }

    @Test
    fun snapshotIgnoresAnUpdateThatWasAlreadyInFlight() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.addInitScript(
                        """
                        (() => {
                          const originalFetch = window.fetch.bind(window);
                          let calls = 0;
                          window.fetch = (...args) => {
                            calls += 1;
                            if (calls === 1) return originalFetch(...args);
                            return new Promise((resolve, reject) => {
                              window.__releaseHarmonFetch = () => {
                                window.__releaseHarmonFetch = null;
                                originalFetch(...args).then(resolve, reject);
                              };
                            });
                          };
                        })();
                        """.trimIndent(),
                    )
                    context.traced("freeze-in-flight") {
                        val page = context.newPage()
                        page.navigate(harness.liveUrl())
                        val firefoxFootprint = processUiRow(page, 100).locator("td").nth(8)
                        assertThat(firefoxFootprint).hasText("1.0 GiB")
                        page.waitForFunction("() => typeof window.__releaseHarmonFetch === 'function'")

                        harness.sendAndWait("sample 2")
                        page.locator(".mode-button").click()
                        page.evaluate("window.__releaseHarmonFetch()")
                        page.waitForTimeout(300.0)

                        assertThat(firefoxFootprint).hasText("1.0 GiB")
                        assertThat(page.locator(".mode-button")).containsText("Snapshot · Resume")
                    }
                }
            }
        }
    }

    @Test
    fun hiddenPageStopsPollingAndResumesWithAFreshRequest() =
        processUiOnLivePage("visibility") { harness, page ->
            val firefoxFootprint = processUiRow(page, 100).locator("td").nth(8)
            page.evaluate(
                """
                Object.defineProperty(document, "visibilityState", {
                  configurable: true, get: () => "hidden"
                });
                document.dispatchEvent(new Event("visibilitychange"));
                """.trimIndent(),
            )
            harness.sendAndWait("sample 2")
            page.waitForTimeout(1_250.0)
            assertThat(firefoxFootprint).hasText("1.0 GiB")

            page.evaluate(
                """
                Object.defineProperty(document, "visibilityState", {
                  configurable: true, get: () => "visible"
                });
                document.dispatchEvent(new Event("visibilitychange"));
                """.trimIndent(),
            )
            assertThat(firefoxFootprint).hasText("2.0 GiB")
        }

    @Test
    fun staleUpdatesKeepTheLastGoodTreeVisible() = processUiOnLivePage("stale") { harness, page ->
        harness.sendAndWait("stale")

        assertThat(page.locator(".notice.error")).containsText("fake collector unavailable")
        assertThat(processUiRow(page, 100).locator("td").nth(9)).hasText("13.0 GiB")
    }

    @Test
    fun lastFullGlobalCoverageStaysDistinctFromCurrentMemberCoverage() =
        processUiOnLivePage("attribution-coverage") { _, page ->
            page.locator(".mode-button").click()
            page.evaluate(
                """
                () => {
                  state.payload = {
                    ...state.payload,
                    system: {
                      ...state.payload.system,
                      processes: {
                        ...state.payload.system.processes,
                        compressedAttributionAvailable: 8,
                        compressedAttributionFailures: 3
                      }
                    },
                    reportText: "Firefox: 1/2 current members with cached values"
                  };
                  draw();
                }
                """.trimIndent(),
            )

            val attributionCard = page.locator(".summary-card").last()
            assertThat(attributionCard.locator(".summary-label"))
                .hasText("Last FULL attribution")
            assertThat(attributionCard).containsText("8 measured · 3 attempts failed")
            assertThat(attributionCard).containsText("s old")

            page.locator(".system-details > summary").click()
            assertThat(page.locator(".system-details"))
                .containsText("Last FULL attribution: 8 measured, 3 attempts failed")
            assertThat(page.locator(".system-details"))
                .containsText("2026-08-12T10:00:00Z")

            page.locator(".report-details > summary").click()
            assertThat(page.locator(".report-details pre"))
                .hasText("Firefox: 1/2 current members with cached values")
        }

    @Test
    fun savedFileSnapshotRunsWithoutTheServerOrRemoteAssets() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.addInitScript(LIVE_HTTP_FORBID_STORAGE_AND_HISTORY)
                    context.traced("file-snapshot") {
                        val networkRequests = Collections.synchronizedList(mutableListOf<String>())
                        val page = context.newPage()
                        page.onRequest { request ->
                            if (request.url().startsWith("http://") || request.url().startsWith("https://")) {
                                networkRequests += request.url()
                            }
                        }
                        page.navigate(harness.snapshotUrl)

                        assertThat(processUiRow(page, 100)).containsText("Firefox")
                        assertThat(processUiRow(page, 100).locator("td").nth(9)).hasText("10.0 GiB")
                        assertThat(page.locator(".mode-button")).isDisabled()
                        assertThat(page.locator(".mode-button")).containsText("Saved snapshot")
                        page.getByText("Full text report").click()
                        assertThat(page.locator(".report-details pre"))
                            .hasText("Fake Harmon report for browser tests.")
                        assertEquals(listOf(false, false), page.evaluate(LIVE_HTTP_POLICY_TOUCHES))
                        assertTrue(networkRequests.isEmpty(), "snapshot requested $networkRequests")
                    }
                }
            }
        }
    }

    @Test
    fun savedFileSnapshotStartsInWebKitWithoutImportMapSupport() {
        Harness().use { harness ->
            onWebKit { browser ->
                browser.desktopContext().use { context ->
                    context.addInitScript(LIVE_HTTP_FORBID_STORAGE_AND_HISTORY)
                    val networkRequests = Collections.synchronizedList(mutableListOf<String>())
                    val page = context.newPage()
                    page.onRequest { request ->
                        if (request.url().startsWith("http://") || request.url().startsWith("https://")) {
                            networkRequests += request.url()
                        }
                    }
                    page.navigate(harness.snapshotUrl)

                    assertThat(processUiRow(page, 100)).containsText("Firefox")
                    assertThat(page.locator(".mode-button")).containsText("Saved snapshot")
                    assertEquals(listOf(false, false), page.evaluate(LIVE_HTTP_POLICY_TOUCHES))
                    assertTrue(networkRequests.isEmpty(), "snapshot requested $networkRequests")
                }
            }
        }
    }

    @Test
    fun fragmentBootstrapClearsTheUrlAndReloadUsesHeaderAuthFromSessionStorage() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    val apiTargets = Collections.synchronizedList(mutableListOf<String>())
                    val authorizationHeaders = Collections.synchronizedList(mutableListOf<String?>())
                    val page = context.newPage()
                    page.onRequest { request ->
                        if (request.url().contains("/api/live")) {
                            apiTargets += request.url()
                            authorizationHeaders += request.headers()["authorization"]
                        }
                    }
                    page.navigate(harness.liveUrl())
                    assertThat(processUiRow(page, 100)).isVisible()

                    assertEquals(harness.baseUrl + "/", page.url())
                    assertTrue(apiTargets.isNotEmpty())
                    assertTrue(apiTargets.all { it == harness.baseUrl + "/api/live?watch=1" })
                    assertTrue(apiTargets.none { harness.token in it })
                    assertTrue(authorizationHeaders.all { it == "Bearer ${harness.token}" })
                    assertTrue(context.cookies().isEmpty())

                    val watchesBeforeReload = harness.watchCount()
                    page.reload()
                    assertThat(processUiRow(page, 100)).isVisible()
                    assertEquals(harness.baseUrl + "/", page.url())
                    assertTrue(harness.watchCount() > watchesBeforeReload)
                }
            }
        }
    }

    @Test
    fun missingAndStaleSessionTokensDoNotRenewTheLease() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    val missingPage = context.newPage()
                    missingPage.navigate(harness.baseUrl + "/")
                    assertThat(missingPage.locator(".notice.error")).containsText("run harmon ui again")
                    assertEquals(0, harness.watchCount())
                }

                browser.desktopContext().use { context ->
                    context.addInitScript(
                        "sessionStorage.setItem('harmon.liveUiToken', '${"b".repeat(64)}')",
                    )
                    val stalePage = context.newPage()
                    stalePage.navigate(harness.baseUrl + "/")
                    assertThat(stalePage.locator(".notice.error")).containsText("run harmon ui again")
                    assertEquals(0, harness.watchCount())
                }
            }
        }
    }

    @Test
    fun sessionTokensAreIsolatedByLoopbackPort() {
        Harness().use { first ->
            Harness().use { second ->
                onChromium { browser ->
                    browser.desktopContext().use { context ->
                        val page = context.newPage()
                        page.navigate(first.liveUrl())
                        assertThat(processUiRow(page, 100)).isVisible()
                        assertTrue(first.watchCount() > 0)

                        page.navigate(second.baseUrl + "/")
                        assertThat(page.locator(".notice.error")).containsText("run harmon ui again")
                        assertEquals(0, second.watchCount())

                        val authenticatedSecondPage = context.newPage()
                        authenticatedSecondPage.navigate(second.liveUrl())
                        assertThat(processUiRow(authenticatedSecondPage, 100)).isVisible()
                        assertTrue(second.watchCount() > 0)
                        assertFalse(first.token == second.token && first.port == second.port)
                    }
                }
            }
        }
    }

    private fun processUiAssertPids(page: Page, expected: List<String>) {
        assertThat(page.locator("tbody tr")).hasCount(expected.size)
        assertEquals(expected, page.locator("tbody tr").all().map { it.getAttribute("data-pid") })
    }

    private fun processUiAssertIdentitySortPersistence(
        label: String,
        ascending: List<String>,
        descending: List<String>,
        descendingAfterPoll: List<String>,
    ) {
        processUiOnLivePage("identity-sort-${label.lowercase()}") { harness, page ->
            val header = processUiSortHeader(page, label)
            header.click()
            processUiAssertPids(page, ascending)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "ascending")

            header.click()
            processUiAssertPids(page, descending)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            page.getByRole(AriaRole.BUTTON, processUiNamed("Memory")).click()
            processUiAssertPids(page, descending)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            page.locator(".column-picker > summary").click()
            val resident = page.getByRole(
                AriaRole.CHECKBOX,
                processUiNamed("Memory · Resident"),
            )
            assertThat(resident).isChecked()
            resident.uncheck()
            assertThat(processUiSortHeader(page, "Resident Self")).hasCount(0)
            processUiAssertPids(page, descending)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            harness.sendAndWait("sample 2")
            processUiAssertPids(page, descendingAfterPoll)
            assertThat(processUiSortHeader(page, "Resident Self")).hasCount(0)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            val mode = page.locator(".mode-button")
            mode.click()
            assertThat(mode).containsText("Snapshot · Resume")
            processUiAssertPids(page, descendingAfterPoll)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            harness.sendAndWait("warming")
            page.waitForTimeout(1_250.0)
            assertThat(mode).containsText("Snapshot · Resume")
            processUiAssertPids(page, descendingAfterPoll)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")

            mode.click()
            assertThat(page.locator(".notice").first()).containsText("fresh baseline")
            processUiAssertPids(page, descendingAfterPoll)
            assertThat(processUiSortHeader(page, "Resident Self")).hasCount(0)
            assertThat(header.locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")
        }
    }

    private fun processUiOnLivePage(
        trace: String,
        block: (Harness, Page) -> Unit,
    ) {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.traced(trace) {
                        val page = context.newPage()
                        page.navigate(harness.liveUrl())
                        assertThat(processUiRow(page, 100)).isVisible()
                        block(harness, page)
                    }
                }
            }
        }
    }
}

private fun processUiNamed(name: String): Page.GetByRoleOptions =
    Page.GetByRoleOptions().setName(name).setExact(true)

private fun processUiSortHeader(page: Page, label: String) =
    page.getByRole(AriaRole.BUTTON, processUiNamed("Sort by $label"))

private fun processUiRow(page: Page, pid: Int) = page.locator("tbody tr[data-pid='$pid']")

private fun processUiRootPids(page: Page): List<String> = page.locator("tbody tr[aria-level='1']")
    .all()
    .map { it.getAttribute("data-pid") }

private val LIVE_HTTP_FORBID_STORAGE_AND_HISTORY =
    """
    (() => {
      window.__harmonStorageTouched = false;
      window.__harmonHistoryTouched = false;
      const denyStorage = () => {
        window.__harmonStorageTouched = true;
        throw new DOMException("storage denied", "SecurityError");
      };
      Storage.prototype.getItem = denyStorage;
      Storage.prototype.setItem = denyStorage;
      history.replaceState = () => {
        window.__harmonHistoryTouched = true;
        throw new DOMException("history denied", "SecurityError");
      };
    })();
    """.trimIndent()

private const val LIVE_HTTP_POLICY_TOUCHES =
    "[Boolean(window.__harmonStorageTouched), Boolean(window.__harmonHistoryTouched)]"
