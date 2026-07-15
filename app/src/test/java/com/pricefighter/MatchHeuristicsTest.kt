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

    @Test
    fun dropsProcessorMotherboardCombos() {
        val listings = listOf(
            listing("Intel Core i7-9700K Desktop Processor 8 Cores"),
            listing("Intel Core i7-9700K CPU + MSI Z390 Motherboard Combo"),
            listing("Intel Core i7-9700K w/ Motherboard and RAM"),
            listing("Intel Core i7-9700K Bundle with Cooler"),
        )
        val kept = MatchHeuristics.byTokenOverlap("Intel Core i7-9700K", listings)
        assertEquals(1, kept.size)
        assertEquals("Intel Core i7-9700K Desktop Processor 8 Cores", kept[0].title)
    }

    @Test
    fun isBundleIsSymmetricAndSparesPlainListings() {
        // Searching for the motherboard should drop the same combo a CPU search drops.
        assertTrue(MatchHeuristics.isBundle("MSI Z390 Motherboard + Intel CPU Combo"))
        assertTrue(MatchHeuristics.isBundle("Intel i7-9700K CPU + Motherboard"))
        assertTrue(MatchHeuristics.isBundle("Lot of 5 Intel Processors"))
        assertFalse(MatchHeuristics.isBundle("Intel Core i7-9700K Desktop Processor"))
        assertFalse(MatchHeuristics.isBundle("MSI Z390-A Pro Motherboard LGA1151"))
    }
}
