package com.pricefighter.data.ebay

import com.pricefighter.data.model.EbayListing
import java.time.LocalDate

/**
 * Assembles the "last N days" of sold listings.
 *
 * Sold searches are sorted by sold/ended date, most recent first (eBay `_sop=13`), so the
 * recent window is the top of page 1 and continues across eBay pages. [collect] keeps the
 * in-window sales from each page and stops once a page is *mostly* older than the cutoff.
 *
 * It deliberately does NOT stop at the first old sale: eBay injects sponsored/promoted
 * listings with arbitrary (often old) sold dates onto every page, so a single old date is
 * not the window boundary. Pure of any HTTP engine — the caller supplies [fetchPage].
 */
object SoldWindow {
    const val DEFAULT_DAYS = 30
    // Pages are fetched at 240 items each, so 5 pages ≈ 1200 sales — enough to reach the
    // 30-day boundary for all but the very highest-volume items, while bounding requests.
    // For an item that sells faster than this cap covers, the 30-day count is a lower bound.
    const val DEFAULT_MAX_PAGES = 5

    suspend fun collect(
        cutoff: LocalDate,
        maxPages: Int = DEFAULT_MAX_PAGES,
        fetchPage: suspend (page: Int) -> List<EbayListing>,
    ): List<EbayListing> {
        val collected = ArrayList<EbayListing>()
        var page = 1
        while (page <= maxPages) {
            val listings = fetchPage(page)
            if (listings.isEmpty()) break

            var inWindow = 0
            var older = 0
            for (listing in listings) {
                val soldDate = listing.soldDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                when {
                    soldDate == null -> collected += listing            // undated: keep it
                    soldDate.isBefore(cutoff) -> older++                // outside window: drop it
                    else -> { collected += listing; inWindow++ }        // inside window: keep it
                }
            }
            // The window has ended once the page's dated sales are majority-older — robust
            // to a few injected outliers, which are dropped above but don't halt paging.
            if (older > inWindow) break
            page++
        }
        return collected
    }
}
