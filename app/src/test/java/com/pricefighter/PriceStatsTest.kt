package com.pricefighter

import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.stats.PriceStats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PriceStatsTest {

    private fun sold(price: Double, daysAgo: Int?): EbayListing = EbayListing(
        title = "Sony WH-1000XM5",
        price = price,
        currency = "USD",
        soldDateIso = daysAgo?.let { LocalDate.of(2026, 6, 22).minusDays(it.toLong()).toString() },
        itemUrl = "https://www.ebay.com/itm/123",
    )

    @Test
    fun computesRangeAverageAndMedian() {
        val report = PriceStats.buildReport(
            searchTerm = "Sony WH-1000XM5",
            soldListings = listOf(sold(100.0, 1), sold(200.0, 2), sold(300.0, 3)),
            activeListings = 42,
            lowestActivePrice = 180.0,
            today = LocalDate.of(2026, 6, 22),
            nowIso = "2026-06-22T00:00:00Z",
        )
        assertEquals(100.0, report.minPrice, 0.001)
        assertEquals(300.0, report.maxPrice, 0.001)
        assertEquals(200.0, report.averagePrice, 0.001)
        assertEquals(200.0, report.medianPrice, 0.001)
        assertEquals(3, report.soldCount)
        assertEquals(42, report.activeListings)
        assertEquals(180.0, report.lowestActivePrice!!, 0.001)
    }

    @Test
    fun medianOfEvenCountAveragesMiddleTwo() {
        val report = PriceStats.buildReport(
            searchTerm = "x",
            soldListings = listOf(sold(10.0, 0), sold(20.0, 0), sold(30.0, 0), sold(40.0, 0)),
            activeListings = 0,
            lowestActivePrice = null,
            today = LocalDate.of(2026, 6, 22),
        )
        assertEquals(25.0, report.medianPrice, 0.001)
    }

    @Test
    fun velocityCountsOnlyLast30Days() {
        val report = PriceStats.buildReport(
            searchTerm = "x",
            soldListings = listOf(sold(10.0, 5), sold(20.0, 29), sold(30.0, 31), sold(40.0, null)),
            activeListings = 0,
            lowestActivePrice = null,
            today = LocalDate.of(2026, 6, 22),
        )
        // 5 and 29 days ago count; 31 days ago and the null date do not.
        assertEquals(2, report.velocityLast30Days)
    }
}
