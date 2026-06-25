package com.pricefighter.data.stats

import com.pricefighter.data.ebay.EbayUrls
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.model.PriceReport
import java.time.Instant
import java.time.LocalDate

/** Computes price-range / average / median / velocity from matched sold listings. */
object PriceStats {

    fun buildReport(
        searchTerm: String,
        soldListings: List<EbayListing>,
        activeListings: Int,
        lowestActivePrice: Double?,
        today: LocalDate = LocalDate.now(),
        nowIso: String = Instant.now().toString(),
    ): PriceReport {
        require(soldListings.isNotEmpty()) { "Need at least one sold listing to build a report." }

        val prices = soldListings.map { it.price }.sorted()
        val cutoff = today.minusDays(30)
        val velocity = soldListings.count { listing ->
            val date = listing.soldDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            date != null && !date.isBefore(cutoff)
        }

        return PriceReport(
            searchTerm = searchTerm,
            soldCount = soldListings.size,
            minPrice = round2(prices.first()),
            maxPrice = round2(prices.last()),
            averagePrice = round2(prices.average()),
            medianPrice = round2(median(prices)),
            velocityLast30Days = velocity,
            activeListings = activeListings,
            lowestActivePrice = lowestActivePrice?.let { round2(it) },
            currency = soldListings.first().currency,
            soldDeeplink = EbayUrls.soldDeeplink(searchTerm),
            generatedAtIso = nowIso,
        )
    }

    /** Median of an already-sorted ascending list. */
    fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
