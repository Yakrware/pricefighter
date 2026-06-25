package com.pricefighter.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.service.AppFunction
import com.pricefighter.ServiceLocator
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.model.EbaySearchResult
import com.pricefighter.data.model.PriceReport

/**
 * The PriceFighter "skill" exposed to Gemini.
 *
 * These functions are designed to be used agentically. A typical "price check"
 * conversation drives them like this:
 *
 *  1. The user says/types/photographs an item; Gemini resolves it to an item + model
 *     (e.g. a photo becomes "Sony WH-1000XM5").
 *  2. Gemini calls [searchSoldListings] for one or more pages and reads the titles,
 *     keeping only the listings that genuinely match the item.
 *  3. Gemini calls [searchActiveListings] once to get the live count and lowest price.
 *  4. Gemini calls [buildPriceReport] with the matched sold listings to get range,
 *     average, median, velocity, and a deeplink — which is also saved to history.
 *
 * [priceCheck] collapses all of that into a single call for quick lookups.
 *
 * Every class that holds `@AppFunction`s is created by the framework with a no-arg
 * constructor, so shared state (the repository) is read from [ServiceLocator].
 */
class PriceCheckFunctions {

    /**
     * Searches eBay for SOLD/completed listings and returns one page of raw results.
     *
     * Results are sorted by **sold date, most recent first**, so page 1 holds the latest
     * sales and each further page goes further back in time (up to 60 per page). The default
     * sample of interest is the **last 30 days**: start at page 1 and keep paging while the
     * sold dates are still within 30 days — that set is also the basis for the 30-day
     * velocity. Inspect [EbaySearchResult.listings], keep only titles that genuinely match
     * the item/model, then pass them to [buildPriceReport].
     *
     * @param searchTerm The item plus model to look up, for example "Sony WH-1000XM5".
     * @param page 1-based page of sold results; omit (or null) for page 1, the most recent sales.
     * @return The parsed sold listings for that page, eBay's total result count, and a deeplink.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchSoldListings(
        appFunctionContext: AppFunctionContext,
        searchTerm: String,
        page: Int? = null,
    ): EbaySearchResult {
        val term = searchTerm.trim()
        if (term.isEmpty()) throw AppFunctionInvalidArgumentException("searchTerm must not be empty.")
        val pageNumber = page ?: 1
        if (pageNumber < 1) throw AppFunctionInvalidArgumentException("page must be 1 or greater.")
        return ServiceLocator.repository.searchSold(term, pageNumber)
    }

    /**
     * Searches eBay for ACTIVE (currently for-sale) listings of an item.
     *
     * Call this once per price check to learn how many listings are currently live and
     * the lowest current asking price. Results are sorted lowest-price-first.
     *
     * @param searchTerm The item plus model to look up, for example "Sony WH-1000XM5".
     * @return Page 1 of active listings, the total active count in [EbaySearchResult.totalResults],
     *   and the lowest current price in [EbaySearchResult.lowestPrice].
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchActiveListings(
        appFunctionContext: AppFunctionContext,
        searchTerm: String,
    ): EbaySearchResult {
        val term = searchTerm.trim()
        if (term.isEmpty()) throw AppFunctionInvalidArgumentException("searchTerm must not be empty.")
        return ServiceLocator.repository.searchActive(term)
    }

    /**
     * Builds and saves a price report from sold listings you have already fetched and filtered.
     *
     * Pass only the sold listings you judged to be a genuine match for the item. The report
     * computes the price range, average, median, and a 30-day sell-through "velocity" from
     * those listings, combines them with the active-listing figures, saves the result to the
     * on-device history, and returns it for you to present to the user.
     *
     * @param searchTerm The item/model these results describe, for example "Sony WH-1000XM5".
     * @param soldListings The matching sold listings gathered from [searchSoldListings].
     * @param activeListings Total active listings, from [EbaySearchResult.totalResults] of [searchActiveListings].
     * @param lowestActivePrice Lowest current asking price, from [EbaySearchResult.lowestPrice], or null if unknown.
     * @return The finished price report, which is also saved to local history.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun buildPriceReport(
        appFunctionContext: AppFunctionContext,
        searchTerm: String,
        soldListings: List<EbayListing>,
        activeListings: Int,
        lowestActivePrice: Double? = null,
    ): PriceReport {
        val term = searchTerm.trim()
        if (term.isEmpty()) throw AppFunctionInvalidArgumentException("searchTerm must not be empty.")
        if (soldListings.isEmpty()) {
            throw AppFunctionInvalidArgumentException("soldListings must contain at least one matching listing.")
        }
        return ServiceLocator.repository.buildAndSaveReport(
            searchTerm = term,
            soldListings = soldListings,
            activeListings = activeListings,
            lowestActivePrice = lowestActivePrice,
        )
    }

    /**
     * Runs a complete price check in a single call: fetches sold and active eBay listings,
     * filters them to the item, and returns a finished, saved report.
     *
     * Prefer this for a quick answer. Use the finer-grained [searchSoldListings],
     * [searchActiveListings], and [buildPriceReport] tools when you want to read and filter
     * the matching listings yourself across multiple pages.
     *
     * @param item The product description, for example "Sony noise-cancelling headphones".
     * @param model The specific model or model number, for example "WH-1000XM5". May be omitted.
     * @return A finished, saved price report.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun priceCheck(
        appFunctionContext: AppFunctionContext,
        item: String,
        model: String? = null,
    ): PriceReport {
        val modelText = model.orEmpty()
        if (item.isBlank() && modelText.isBlank()) {
            throw AppFunctionInvalidArgumentException("Provide at least an item or a model to price check.")
        }
        return ServiceLocator.repository.priceCheck(item.trim(), modelText.trim())
    }
}
