package dev.yoda.harmon.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessTreeUiTest {
    @Test
    fun defaultsToRecursiveMemoryTotalsAndShowsPidSelfAndTotal() = onLivePage("totals") { _, page ->
        val rows = page.locator("tbody tr")
        assertThat(rows.first()).hasAttribute("data-pid", "100")
        assertThat(rows.nth(1)).hasAttribute("data-pid", "101")

        val firefox = page.locator("tbody tr[data-pid='100']")
        assertThat(firefox.locator("td").nth(0)).hasText("100")
        assertThat(firefox.locator("td").nth(2)).hasText("5.0%")
        assertThat(firefox.locator("td").nth(3)).hasText("50.0%")
        assertThat(firefox.locator("td").nth(4)).hasText("1.0 GiB")
        assertThat(firefox.locator("td").nth(5)).hasText("10.0 GiB")
        assertThat(
            page.getByRole(
                com.microsoft.playwright.options.AriaRole.BUTTON,
                Page.GetByRoleOptions().setName("Sort by Memory Total"),
            ).locator("xpath=.."),
        ).hasAttribute("aria-sort", "descending")

        assertThat(page.locator("tbody tr[data-pid='103']")).isVisible()
        assertThat(page.locator("tbody tr[data-pid='300']")).containsText("partial")
    }

    @Test
    fun sortingByAnotherTotalRecursesWithinEverySiblingSet() = onLivePage("sorting") { _, page ->
        page.getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Sort by CPU Total"),
        ).click()

        val rows = page.locator("tbody tr")
        assertThat(rows.first()).hasAttribute("data-pid", "200")
        assertThat(rows.nth(1)).hasAttribute("data-pid", "100")
        assertThat(rows.nth(2)).hasAttribute("data-pid", "101")
        assertThat(rows.nth(4)).hasAttribute("data-pid", "102")
    }

    @Test
    fun searchKeepsAncestorsAndTheMatchedSubtree() = onLivePage("search") { _, page ->
        val search = page.getByRole(
            com.microsoft.playwright.options.AriaRole.SEARCHBOX,
            Page.GetByRoleOptions().setName("Search processes"),
        )
        search.fill("103")
        assertPids(page, listOf("100", "101", "103"))

        search.fill("Firefox")
        assertPids(page, listOf("100", "101", "103", "102"))
        assertThat(page.locator("tbody tr[data-pid='200']")).hasCount(0)
    }

    @Test
    fun treeControlsCollapseAndExpandByProcess() = onLivePage("tree-controls") { _, page ->
        page.getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Collapse Firefox (PID 100)"),
        ).click()
        assertThat(page.locator("tbody tr[data-pid='101']")).hasCount(0)

        page.getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Expand Firefox (PID 100)"),
        ).click()
        assertThat(page.locator("tbody tr[data-pid='101']")).isVisible()
    }

    @Test
    fun snapshotFreezesAndResumeAppliesTheLatestFakeSample() = onLivePage("freeze-resume") { harness, page ->
        val firefoxMemory = page.locator("tbody tr[data-pid='100'] td").nth(4)
        assertThat(firefoxMemory).hasText("1.0 GiB")

        val mode = page.locator(".mode-button")
        mode.click()
        assertThat(mode).containsText("Snapshot · Resume")
        harness.send("sample 2")
        page.waitForFunction("() => new Promise(resolve => setTimeout(() => resolve(true), 1250))")
        assertThat(firefoxMemory).hasText("1.0 GiB")

        mode.click()
        assertThat(firefoxMemory).hasText("2.0 GiB")
        assertThat(mode).containsText("Live · Snapshot")
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
                        val firefoxMemory = page.locator("tbody tr[data-pid='100'] td").nth(4)
                        assertThat(firefoxMemory).hasText("1.0 GiB")
                        page.waitForFunction("() => typeof window.__releaseHarmonFetch === 'function'")

                        harness.sendAndWait("sample 2")
                        page.locator(".mode-button").click()
                        page.evaluate("window.__releaseHarmonFetch()")
                        page.waitForFunction(
                            "() => new Promise(resolve => setTimeout(() => resolve(true), 300))",
                        )

                        assertThat(firefoxMemory).hasText("1.0 GiB")
                        assertThat(page.locator(".mode-button")).containsText("Snapshot · Resume")
                    }
                }
            }
        }
    }

    @Test
    fun staleUpdatesKeepTheLastGoodTreeVisible() = onLivePage("stale") { harness, page ->
        harness.send("stale")

        assertThat(page.locator(".notice.error")).containsText("fake collector unavailable")
        assertThat(page.locator("tbody tr[data-pid='100'] td").nth(5)).hasText("13.0 GiB")
    }

    @Test
    fun savedFileSnapshotRunsWithoutTheServerOrRemoteAssets() {
        Harness().use { harness ->
            onChromium { browser ->
                browser.desktopContext().use { context ->
                    context.traced("file-snapshot") {
                        val page = context.newPage()
                        page.navigate(harness.snapshotUrl)

                        assertThat(page.locator("tbody tr[data-pid='100']")).containsText("Firefox")
                        assertThat(page.locator("tbody tr[data-pid='100'] td").nth(5)).hasText("10.0 GiB")
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

                    assertThat(page.locator("tbody tr[data-pid='100']")).containsText("Firefox")
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
                        assertThat(page.locator("tbody tr[data-pid='100']")).isVisible()
                        block(harness, page)
                    }
                }
            }
        }
    }
}
