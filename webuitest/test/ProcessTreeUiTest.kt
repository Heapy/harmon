package dev.yoda.harmon.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessTreeUiTest {
    @Test
    fun defaultsToOverviewAndRecursiveCpuTotalsWithPinnedIdentityColumns() =
        onLivePage("overview-totals") { _, page ->
            val rows = page.locator("tbody tr")
            assertThat(rows.first()).hasAttribute("data-pid", "200")
            assertThat(rows.nth(1)).hasAttribute("data-pid", "100")

            val firefox = row(page, 100)
            assertThat(firefox.locator("td").nth(0)).hasText("100")
            assertThat(firefox.locator("td").nth(2)).hasText("5.0%")
            assertThat(firefox.locator("td").nth(3)).hasText("50.0%")
            assertThat(firefox.locator("td").nth(8)).hasText("1.0 GiB")
            assertThat(firefox.locator("td").nth(9)).hasText("10.0 GiB")
            assertThat(sortHeader(page, "CPU Total").locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")
            assertThat(page.getByRole(AriaRole.BUTTON, named("Overview")))
                .hasClass("preset-button active")
            assertThat(page.locator("th.pid-column")).hasCSS("position", "sticky")
            assertThat(page.locator("th.process-column")).hasCSS("position", "sticky")

            assertThat(row(page, 103)).isVisible()
            assertThat(row(page, 300)).containsText("partial")
        }

    @Test
    fun selfAndTotalSortIndependentlyInsideEverySiblingSet() =
        onLivePage("independent-sorting") { _, page ->
            sortHeader(page, "CPU Self").click()
            assertPids(page, listOf("200", "100", "102", "101", "103", "300", "301"))

            sortHeader(page, "CPU Total").click()
            assertPids(page, listOf("200", "100", "101", "103", "102", "300", "301"))
        }

    @Test
    fun unavailableValuesStayAtTheBottomButKnownPartialTotalsStillSort() =
        onLivePage("availability-sorting") { _, page ->
            sortHeader(page, "CPU Self").click()
            sortHeader(page, "CPU Self").click()
            val ascendingRoots = rootPids(page)
            assertEquals(listOf("100", "200", "300"), ascendingRoots)

            sortHeader(page, "CPU Total").click()
            sortHeader(page, "CPU Total").click()
            val totalAscendingRoots = rootPids(page)
            assertEquals(listOf("300", "100", "200"), totalAscendingRoots)
            assertThat(row(page, 300)).containsText("partial")
        }

    @Test
    fun presetsExposeEveryMetricGroupAndLifetimePeakIsSelfOnly() =
        onLivePage("column-presets") { _, page ->
            page.getByRole(AriaRole.BUTTON, named("Memory")).click()
            assertThat(sortHeader(page, "Resident Self")).isVisible()
            assertThat(sortHeader(page, "Compressed / paged Total")).isVisible()
            assertThat(sortHeader(page, "VM regions Total")).isVisible()
            assertThat(sortHeader(page, "Lifetime peak Self")).isVisible()
            assertThat(page.getByRole(AriaRole.BUTTON, named("Sort by Lifetime peak Total")))
                .hasCount(0)

            page.getByRole(AriaRole.BUTTON, named("I/O")).click()
            assertThat(sortHeader(page, "Disk read Total")).isVisible()
            assertThat(sortHeader(page, "Logical writes Self")).isVisible()
            assertThat(sortHeader(page, "Page-ins Total")).isVisible()

            page.getByRole(AriaRole.BUTTON, named("Activity")).click()
            assertThat(sortHeader(page, "Wakeups Total")).isVisible()
            assertThat(sortHeader(page, "Syscalls Self")).isVisible()
            assertThat(sortHeader(page, "Threads Total")).isVisible()

            page.getByRole(AriaRole.BUTTON, named("Compute / Energy")).click()
            assertThat(sortHeader(page, "Instructions Total")).isVisible()
            assertThat(sortHeader(page, "Cycles Self")).isVisible()
            assertThat(sortHeader(page, "Watts Total")).isVisible()
            assertThat(sortHeader(page, "Battery impact Total")).isVisible()
        }

    @Test
    fun searchKeepsAncestorsAndTheMatchedSubtree() = onLivePage("search") { _, page ->
        val search = page.getByRole(AriaRole.SEARCHBOX, named("Search processes"))
        search.fill("103")
        assertPids(page, listOf("100", "101", "103"))

        search.fill("Firefox")
        assertPids(page, listOf("100", "101", "103", "102"))
        assertThat(row(page, 200)).hasCount(0)
    }

    @Test
    fun treeControlsCollapseAndExpandByProcess() = onLivePage("tree-controls") { _, page ->
        page.getByRole(AriaRole.BUTTON, named("Collapse Firefox (PID 100)")).click()
        assertThat(row(page, 101)).hasCount(0)

        page.getByRole(AriaRole.BUTTON, named("Expand Firefox (PID 100)")).click()
        assertThat(row(page, 101)).isVisible()
    }

    @Test
    fun selectionsSortSearchAndExpansionSurvivePollWarmingSnapshotAndResume() =
        onLivePage("persistent-state") { harness, page ->
            page.getByRole(AriaRole.BUTTON, named("Memory")).click()
            sortHeader(page, "Resident Self").click()
            page.getByRole(AriaRole.BUTTON, named("Collapse Firefox (PID 100)")).click()
            val search = page.getByRole(AriaRole.SEARCHBOX, named("Search processes"))
            search.fill("Firefox")

            harness.sendAndWait("sample 2")
            assertThat(row(page, 100).locator("td").nth(2)).hasText("2.0 GiB")
            assertThat(search).hasValue("Firefox")
            assertThat(page.getByRole(AriaRole.BUTTON, named("Memory")))
                .hasClass("preset-button active")
            assertThat(sortHeader(page, "Resident Self").locator("xpath=.."))
                .hasAttribute("aria-sort", "descending")
            assertThat(row(page, 104)).isVisible()

            search.fill("")
            assertThat(row(page, 101)).hasCount(0)

            val mode = page.locator(".mode-button")
            mode.click()
            harness.sendAndWait("warming")
            page.waitForTimeout(1_250.0)
            assertThat(mode).containsText("Snapshot · Resume")
            assertThat(row(page, 100).locator("td").nth(2)).hasText("2.0 GiB")

            mode.click()
            assertThat(page.locator(".notice").first()).containsText("fresh baseline")
            assertThat(page.getByRole(AriaRole.BUTTON, named("Memory")))
                .hasClass("preset-button active")
            assertThat(row(page, 101)).hasCount(0)
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
                        val firefoxFootprint = row(page, 100).locator("td").nth(8)
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
        onLivePage("visibility") { harness, page ->
            val firefoxFootprint = row(page, 100).locator("td").nth(8)
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
    fun staleUpdatesKeepTheLastGoodTreeVisible() = onLivePage("stale") { harness, page ->
        harness.sendAndWait("stale")

        assertThat(page.locator(".notice.error")).containsText("fake collector unavailable")
        assertThat(row(page, 100).locator("td").nth(9)).hasText("13.0 GiB")
    }

    @Test
    fun savedFileSnapshotRunsWithoutTheServerOrRemoteAssets() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.traced("file-snapshot") {
                        val page = context.newPage()
                        page.navigate(harness.snapshotUrl)

                        assertThat(row(page, 100)).containsText("Firefox")
                        assertThat(row(page, 100).locator("td").nth(9)).hasText("10.0 GiB")
                        assertThat(page.locator(".mode-button")).isDisabled()
                        assertThat(page.locator(".mode-button")).containsText("Saved snapshot")
                        page.getByText("Full text report").click()
                        assertThat(page.locator(".report-details pre"))
                            .hasText("Fake Harmon report for browser tests.")
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
                    val page = context.newPage()
                    page.navigate(harness.snapshotUrl)

                    assertThat(row(page, 100)).containsText("Firefox")
                    assertThat(page.locator(".mode-button")).containsText("Saved snapshot")
                }
            }
        }
    }

    @Test
    fun loopbackPageRejectsRequestsWithoutTheManifestToken() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    val response = context.request().get(harness.baseUrl + "/")

                    assertEquals(403, response.status())
                }
            }
        }
    }

    private fun assertPids(page: Page, expected: List<String>) {
        assertThat(page.locator("tbody tr")).hasCount(expected.size)
        assertEquals(expected, page.locator("tbody tr").all().map { it.getAttribute("data-pid") })
    }

    private fun onLivePage(
        trace: String,
        block: (Harness, Page) -> Unit,
    ) {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.traced(trace) {
                        val page = context.newPage()
                        page.navigate(harness.liveUrl())
                        assertThat(row(page, 100)).isVisible()
                        block(harness, page)
                    }
                }
            }
        }
    }
}

private fun named(name: String): Page.GetByRoleOptions =
    Page.GetByRoleOptions().setName(name).setExact(true)

private fun sortHeader(page: Page, label: String) =
    page.getByRole(AriaRole.BUTTON, named("Sort by $label"))

private fun row(page: Page, pid: Int) = page.locator("tbody tr[data-pid='$pid']")

private fun rootPids(page: Page): List<String> = page.locator("tbody tr[aria-level='1']")
    .all()
    .map { it.getAttribute("data-pid") }
