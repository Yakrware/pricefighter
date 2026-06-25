package com.pricefighter.data.ebay

import com.pricefighter.data.model.EbaySearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads public eBay search pages and returns them parsed. The actual HTTP transport is
 * supplied as a [PageFetcher] so the on-device path can use Cronet (Chrome's network
 * stack) while JVM tests use OkHttp — no eBay account or API key is involved.
 */
class EbayClient(
    private val fetcher: PageFetcher,
) {
    suspend fun search(
        term: String,
        soldOnly: Boolean,
        page: Int = 1,
        sortLowestPriceFirst: Boolean = false,
        itemsPerPage: Int = EbayUrls.ITEMS_PER_PAGE,
    ): EbaySearchResult = withContext(Dispatchers.IO) {
        require(term.isNotBlank()) { "search term must not be blank" }
        val url = EbayUrls.search(term, soldOnly, page, sortLowestPriceFirst, itemsPerPage)
        val parsed = EbayParser.parse(fetcher.get(url, referer = "https://www.ebay.com/"))
        val lowest = if (!soldOnly) parsed.listings.minOfOrNull { it.price } else null
        EbaySearchResult(
            searchTerm = term,
            soldOnly = soldOnly,
            page = page,
            totalResults = parsed.totalResults,
            lowestPrice = lowest,
            listings = parsed.listings,
            deeplink = EbayUrls.search(term, soldOnly, page),
        )
    }
}
