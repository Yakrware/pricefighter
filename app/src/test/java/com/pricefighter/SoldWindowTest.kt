package com.pricefighter

import com.pricefighter.data.ebay.SoldWindow
import com.pricefighter.data.model.EbayListing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SoldWindowTest {

    private val cutoff = LocalDate.of(2026, 6, 23).minusDays(30) // 2026-05-24

    private fun sold(date: String?) = EbayListing(
        title = "Sony WH-1000XM5",
        price = 100.0,
        currency = "USD",
        soldDateIso = date,
        itemUrl = "https://www.ebay.com/itm/0",
    )

    @Test
    fun outlierOldSaleDoesNotStopPagingButMajorityOlderDoes() = runBlocking {
        val pages = mapOf(
            // Page 1 carries one injected ancient sale (like an eBay sponsored listing).
            1 to (List(59) { sold("2026-06-20") } + listOf(sold("2026-01-13"))),
            2 to List(60) { sold("2026-06-05") },  // all within window
            3 to List(60) { sold("2026-04-01") },  // all older → window has ended here
            4 to List(60) { sold("2026-03-01") },  // must not be fetched
        )
        val fetched = mutableListOf<Int>()

        val result = SoldWindow.collect(cutoff, maxPages = 10) { page ->
            fetched += page
            pages[page] ?: emptyList()
        }

        // The lone old sale on page 1 must NOT stop paging; paging stops after the
        // majority-older page 3.
        assertEquals(listOf(1, 2, 3), fetched)
        // 59 in-window from p1 + 60 from p2 + 0 from p3 = 119; the outlier is dropped.
        assertEquals(119, result.size)
        assertTrue(result.none { it.soldDateIso == "2026-01-13" })
    }

    @Test
    fun respectsMaxPagesForHighVolumeItems() = runBlocking {
        val fetched = mutableListOf<Int>()
        val result = SoldWindow.collect(cutoff, maxPages = 3) { page ->
            fetched += page
            List(60) { sold("2026-06-20") } // every page within window → bounded by maxPages
        }
        assertEquals(180, result.size)
        assertEquals(listOf(1, 2, 3), fetched)
    }

    @Test
    fun keepsUndatedSalesWithoutStopping() = runBlocking {
        val result = SoldWindow.collect(cutoff, maxPages = 2) { page ->
            when (page) {
                1 -> listOf(sold("2026-06-20"), sold(null), sold("2026-06-18"))
                else -> emptyList()
            }
        }
        assertEquals(3, result.size) // undated sale kept, paging continues until empty page
    }
}
