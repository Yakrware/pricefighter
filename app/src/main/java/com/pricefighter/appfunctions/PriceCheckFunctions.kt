package com.pricefighter.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.service.AppFunction
import com.pricefighter.ServiceLocator
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.model.EbaySearchResult
import com.pricefighter.data.model.PriceCheckPlan
import com.pricefighter.data.model.PriceReport

/**
 * The PriceFighter "skill" exposed to Gemini.
 *
 * These functions are designed to be used agentically, called **in order**, with the agent doing
 * the judgement in between — the tools fetch raw data, the agent decides what counts:
 *
 *  1. The user says/types/photographs an item; the agent resolves it to an item + model
 *     (e.g. a photo becomes "Sony WH-1000XM5").
 *  2. The agent calls [searchSoldListings] (one or more pages) to get **raw** results, then reads
 *     the titles and **keeps only genuine matches** — dropping empty boxes ("box only"), broken /
 *     for-parts units, accessories (cases, chargers, cables, straps…), combos/bundles that include
 *     other hardware (a CPU sold with a motherboard), and different models. This filtering step is
 *     the agent's job; the tool does not do it.
 *  3. The agent calls [searchActiveListings] once to get the live count and lowest price.
 *  4. The agent calls [buildPriceReport] with **only the matches it kept** to get range, average,
 *     median, velocity, and a deeplink — which is also saved to history.
 *
 * [howToPriceCheck] returns that plan as a tool result — a landing spot for the "price check"
 * command phrase that routes the agent into the ordered flow above. There is deliberately no
 * do-everything tool: filtering out poor matches is the agent's judgement, not a shortcut.
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
     * velocity. Results are raw and unfiltered: inspect [EbaySearchResult.listings] and keep only
     * titles that are genuinely this item/model — drop empty boxes ("box only"), for-parts/broken
     * units, accessories (cases, chargers, cables, straps…), combos/bundles that include other
     * hardware (a CPU sold with a motherboard), and other models — then pass the kept listings to
     * [buildPriceReport].
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
     * Start here for a "price check": returns the recommended step-by-step plan for pricing an
     * item accurately with the other tools. This call does no work itself — it just orients you.
     *
     * A price check is a few tool calls made in order, and **you** filter the results in between:
     * fetch raw sold listings, keep only the genuine matches, then build the report. There is no
     * do-everything shortcut on purpose — filtering out poor matches is the agent's judgement.
     *
     * @return An overview plus the ordered steps to follow, each naming the tool to call.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun howToPriceCheck(appFunctionContext: AppFunctionContext): PriceCheckPlan =
        PriceCheckPlan(
            overview = "Price-check an item as a few ordered tool calls; you filter the results " +
                "in between. There is no single do-it-all tool — that keeps the matching accurate.",
            steps = listOf(
                "1. Resolve the user's item to a brand + model, e.g. \"Sony WH-1000XM5\".",
                "2. Call searchSoldListings(searchTerm). Page 1 is the most recent sales; page " +
                    "again while the sold dates are within the last 30 days. Results are RAW.",
                "3. Read the titles and keep ONLY genuine matches. Drop empty boxes (\"box only\"), " +
                    "for-parts/broken units, accessories (case, cover, charger, cable, strap…), " +
                    "manuals, combos/bundles/lots that include other hardware (e.g. a CPU sold " +
                    "with a motherboard), and different models or variants.",
                "4. Call searchActiveListings(searchTerm) once for the live count and lowest price.",
                "5. Call buildPriceReport(searchTerm, keptSoldListings, activeListings, " +
                    "lowestActivePrice). It computes range/average/median/velocity, saves to " +
                    "history, and returns the report to present to the user.",
            ),
        )
}
