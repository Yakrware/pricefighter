package com.pricefighter.data.model

import androidx.appfunctions.AppFunctionSerializable

/**
 * A single eBay listing parsed from a search-results page.
 *
 * These objects cross the process boundary to the calling agent (Gemini), so the
 * agent can read each title and decide whether it genuinely matches the item the
 * user asked about before the matching subset is summarized into a report.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EbayListing(
    /** The listing title exactly as shown on eBay. */
    val title: String,
    /** Numeric price in [currency]: the sold price for a completed listing, or the asking price for an active one. */
    val price: Double,
    /** ISO-4217 currency code, e.g. "USD". */
    val currency: String,
    /** Sale date as an ISO date (yyyy-MM-dd) for sold listings, or null for active listings / when eBay did not expose a date. */
    val soldDateIso: String?,
    /** Canonical URL of the individual eBay listing. */
    val itemUrl: String,
)

/**
 * One page of parsed eBay results for a single search.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EbaySearchResult(
    /** The search term that produced these results. */
    val searchTerm: String,
    /** True when these are completed/sold listings; false for active listings. */
    val soldOnly: Boolean,
    /** 1-based page number these results came from. */
    val page: Int,
    /** Total results eBay reports for this query across all pages, or -1 if it could not be parsed. */
    val totalResults: Int,
    /** For an active search, the lowest asking price seen on this page; null for sold searches. */
    val lowestPrice: Double?,
    /** Individual listings parsed from this page (up to 60). */
    val listings: List<EbayListing>,
    /** Shareable eBay URL that reproduces this exact filtered search. */
    val deeplink: String,
)

/**
 * The finished price-check report summarizing an item's sold-market and active-market state.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PriceReport(
    /** The item/model that was priced. */
    val searchTerm: String,
    /** Number of sold listings the statistics are based on. */
    val soldCount: Int,
    /** Lowest sold price. */
    val minPrice: Double,
    /** Highest sold price. */
    val maxPrice: Double,
    /** Mean sold price. */
    val averagePrice: Double,
    /** Median sold price. */
    val medianPrice: Double,
    /** Count of the supplied sold listings whose sale date falls within the last 30 days (a sell-through "velocity"). */
    val velocityLast30Days: Int,
    /** Number of currently active listings eBay reports for this item. */
    val activeListings: Int,
    /** Lowest current asking price among active listings, or null if unknown. */
    val lowestActivePrice: Double?,
    /** ISO-4217 currency code for every price in this report. */
    val currency: String,
    /** eBay deeplink reproducing the sold/completed search this report summarizes. */
    val soldDeeplink: String,
    /** ISO-8601 timestamp of when this report was generated. */
    val generatedAtIso: String,
)

/**
 * The recommended plan for running a price check with the other tools — returned by the
 * "how to" tool so the calling agent orients itself before acting, rather than reaching for a
 * lossy do-everything shortcut.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PriceCheckPlan(
    /** One-line summary of the approach. */
    val overview: String,
    /** The ordered steps to follow, each naming the tool to call and the judgement to apply. */
    val steps: List<String>,
)
