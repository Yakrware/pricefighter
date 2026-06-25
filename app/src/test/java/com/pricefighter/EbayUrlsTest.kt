package com.pricefighter

import com.pricefighter.data.ebay.EbayUrls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EbayUrlsTest {

    @Test
    fun soldSearchIncludesConditionFilterAndSoldFlags() {
        val url = EbayUrls.search("Sony WH-1000XM5", soldOnly = true)
        assertTrue(url.contains("_nkw=Sony+WH-1000XM5"))
        assertTrue(url.contains("LH_Sold=1"))
        assertTrue(url.contains("LH_Complete=1"))
        // Every sellable condition (new/open-box/refurbished/used); excludes parts (7000).
        assertTrue(url.contains("LH_ItemCondition=1000%7C1500%7C1750%7C2000%7C2010%7C2020%7C2030%7C2500%7C3000"))
        assertFalse(url.contains("7000")) // "For parts or not working" must not be included
    }

    @Test
    fun activeSearchIsConditionFilteredButNotSold() {
        val url = EbayUrls.search("DJI Mini 4 Pro", soldOnly = false, sortLowestPriceFirst = true)
        assertFalse(url.contains("LH_Sold=1"))
        assertTrue(url.contains("LH_ItemCondition=1000%7C1500%7C1750%7C2000%7C2010%7C2020%7C2030%7C2500%7C3000"))
        assertTrue(url.contains("_sop=15")) // price + shipping: lowest first
    }
}
