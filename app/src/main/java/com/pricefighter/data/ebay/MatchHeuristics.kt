package com.pricefighter.data.ebay

import com.pricefighter.data.model.EbayListing
import kotlin.math.ceil

/**
 * Cheap, offline title matching. Used to weed out obviously-wrong listings (accessories,
 * wrong models, parts) by token overlap. This is the fallback the Nano agent drops back to
 * when on-device Gemini Nano isn't available to judge matches semantically.
 */
object MatchHeuristics {

    /** Keep listings whose title contains at least ~60% of the search tokens. */
    fun byTokenOverlap(term: String, listings: List<EbayListing>): List<EbayListing> {
        val tokens = term.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return listings
        val needed = ceil(tokens.size * 0.6).toInt().coerceAtLeast(1)
        return listings.filter { listing ->
            val title = listing.title.lowercase()
            tokens.count { title.contains(it) } >= needed
        }
    }
}
