package com.pricefighter

import com.pricefighter.data.ebay.MatchHeuristics
import com.pricefighter.data.model.EbayListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchHeuristicsTest {

    private fun listing(title: String) = EbayListing(title, 100.0, "USD", null, "https://e/1")

    @Test
    fun dropsBoxOnlyPartsAndBrokenListings() {
        val listings = listOf(
            listing("Sony WH-1000XM5 Wireless Headphones Black"),
            listing("Sony WH-1000XM5 Box Only No Headphones"),
            listing("Sony WH-1000XM5 For Parts Not Working"),
            listing("Sony WH-1000XM5 Replacement Case Only"),
        )
        val kept = MatchHeuristics.byTokenOverlap("Sony WH-1000XM5", listings)
        assertEquals(1, kept.size)
        assertEquals("Sony WH-1000XM5 Wireless Headphones Black", kept[0].title)
    }

    @Test
    fun keepsGenuineMatchAndDropsWrongModel() {
        val listings = listOf(
            listing("Sony WH-1000XM5 Headphones"),
            listing("Apple AirPods Pro 2"),
        )
        val kept = MatchHeuristics.byTokenOverlap("Sony WH-1000XM5", listings)
        assertEquals(1, kept.size)
        assertEquals("Sony WH-1000XM5 Headphones", kept[0].title)
    }

    @Test
    fun isJunkFlagsBoxAndParts() {
        assertTrue(MatchHeuristics.isJunk("Apple iPhone 13 Box Only"))
        assertTrue(MatchHeuristics.isJunk("Nintendo Switch For Parts Not Working"))
        assertFalse(MatchHeuristics.isJunk("Sony WH-1000XM5 Excellent Condition"))
    }
}
