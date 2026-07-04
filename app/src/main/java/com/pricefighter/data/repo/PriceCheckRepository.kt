package com.pricefighter.data.repo

import com.pricefighter.data.db.HistoryDao
import com.pricefighter.data.db.HistoryEntity
import com.pricefighter.data.db.IncludedListing
import com.pricefighter.data.ebay.EbayClient
import com.pricefighter.data.ebay.EbayUrls
import com.pricefighter.data.ebay.MatchHeuristics
import com.pricefighter.data.ebay.OkHttpPageFetcher
import com.pricefighter.data.ebay.SoldWindow
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.model.EbaySearchResult
import com.pricefighter.data.model.PriceReport
import com.pricefighter.data.stats.PriceStats
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Single source of truth for price checks: wraps the eBay scraper, the statistics,
 * and the local history database. Both the UI and the Gemini-callable app functions
 * go through this class.
 */
class PriceCheckRepository(
    private val dao: HistoryDao,
    // The app overrides this with a Cronet-backed client; the default OkHttp client is
    // for JVM tests and non-network use (e.g. building a report from supplied listings).
    private val ebay: EbayClient = EbayClient(OkHttpPageFetcher()),
) {
    val history: Flow<List<HistoryEntity>> = dao.observeAll()

    suspend fun searchSold(
        term: String,
        page: Int,
        itemsPerPage: Int = EbayUrls.ITEMS_PER_PAGE,
    ): EbaySearchResult =
        ebay.search(term, soldOnly = true, page = page, itemsPerPage = itemsPerPage)

    /** Active listings sorted lowest-price-first, so [EbaySearchResult.lowestPrice] is meaningful. */
    suspend fun searchActive(term: String): EbaySearchResult =
        ebay.search(term, soldOnly = false, page = 1, sortLowestPriceFirst = true)

    /**
     * Sold listings from the last [days] days. Sold results come back newest-first, so this
     * pages through eBay pages collecting sales until the dates fall outside the window (or
     * [maxPages] is reached, bounding the work for very high-volume items).
     */
    suspend fun fetchSoldWithinDays(
        term: String,
        days: Int = SoldWindow.DEFAULT_DAYS,
        maxPages: Int = SoldWindow.DEFAULT_MAX_PAGES,
    ): List<EbayListing> {
        val cutoff = LocalDate.now().minusDays(days.toLong())
        return SoldWindow.collect(cutoff, maxPages) { page ->
            searchSold(term, page, itemsPerPage = EbayUrls.WINDOW_ITEMS_PER_PAGE).listings
        }
    }

    suspend fun buildAndSaveReport(
        searchTerm: String,
        soldListings: List<EbayListing>,
        activeListings: Int,
        lowestActivePrice: Double?,
    ): PriceReport {
        val report = PriceStats.buildReport(searchTerm, soldListings, activeListings, lowestActivePrice)
        dao.insert(report.toEntity(soldListings))
        return report
    }

    /**
     * One-shot convenience used by the high-level `priceCheck` function: gather the last
     * 30 days of sold listings (paging eBay pages as needed) plus active listings, keep
     * listings whose titles overlap the search tokens, then build and save the report.
     */
    suspend fun priceCheck(item: String, model: String): PriceReport {
        val term = listOf(item, model).filter { it.isNotBlank() }.joinToString(" ").trim()
        require(term.isNotBlank()) { "Provide at least an item or a model." }

        // Default sample = last 30 days of sales; fall back to page 1 for slow-moving items.
        val soldWindow = fetchSoldWithinDays(term).ifEmpty { searchSold(term, page = 1).listings }
        val active = searchActive(term)
        val matched = MatchHeuristics.byTokenOverlap(term, soldWindow).ifEmpty { soldWindow }
        return buildAndSaveReport(
            searchTerm = term,
            soldListings = matched,
            activeListings = active.totalResults,
            lowestActivePrice = active.lowestPrice,
        )
    }

    suspend fun deleteEntry(id: Long) = dao.delete(id)

    suspend fun clearHistory() = dao.clear()
}

private fun PriceReport.toEntity(included: List<EbayListing>): HistoryEntity = HistoryEntity(
    searchTerm = searchTerm,
    soldCount = soldCount,
    minPrice = minPrice,
    maxPrice = maxPrice,
    averagePrice = averagePrice,
    medianPrice = medianPrice,
    velocityLast30Days = velocityLast30Days,
    activeListings = activeListings,
    lowestActivePrice = lowestActivePrice,
    currency = currency,
    soldDeeplink = soldDeeplink,
    createdAtEpochMs = System.currentTimeMillis(),
    included = included.map { IncludedListing(it.title, it.price, it.soldDateIso, it.itemUrl) },
)
