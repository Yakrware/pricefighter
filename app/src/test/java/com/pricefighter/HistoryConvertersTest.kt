package com.pricefighter

import com.pricefighter.data.db.HistoryConverters
import com.pricefighter.data.db.IncludedListing
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryConvertersTest {

    private val converters = HistoryConverters()

    @Test
    fun roundTripsIncludedListings() {
        val items = listOf(
            IncludedListing("Sony WH-1000XM5 Black", 199.99, "2026-06-20", "https://www.ebay.com/itm/111"),
            IncludedListing("Sony WH-1000XM5 (no sold date)", 210.0, null, "https://www.ebay.com/itm/222"),
        )
        assertEquals(items, converters.decode(converters.encode(items)))
    }

    @Test
    fun handlesEmptyList() {
        assertEquals("", converters.encode(emptyList()))
        assertEquals(emptyList<IncludedListing>(), converters.decode(""))
    }

    @Test
    fun aStraySeparatorCharInATitleDoesNotCorruptTheRecord() {
        val items = listOf(IncludedListing("weirdtitlehere", 5.0, null, "u"))
        val decoded = converters.decode(converters.encode(items))
        assertEquals(1, decoded.size)
        assertEquals(5.0, decoded[0].price, 0.001)
    }
}
