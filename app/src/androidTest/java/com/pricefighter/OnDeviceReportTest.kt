package com.pricefighter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricefighter.data.db.AppDatabase
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.repo.PriceCheckRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device test: drives the real repository against the real on-device Room database
 * (the same `pricefighter.db` the app reads), exactly as `buildPriceReport` does — then
 * leaves a saved report behind so the app's history UI displays it.
 *
 * The listings are real Sony WH-1000XM5 sold results captured from live eBay.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceReportTest {

    private fun sold(price: Double, title: String) = EbayListing(
        title = title,
        price = price,
        currency = "USD",
        soldDateIso = "2026-06-22",
        itemUrl = "https://www.ebay.com/itm/0",
    )

    @Test
    fun buildAndPersistRealReport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PriceCheckRepository(AppDatabase.get(context).historyDao())

        val listings = listOf(
            sold(139.99, "Sony WH-1000XM5SA Wireless Active Noise-Cancelling Headphones"),
            sold(176.00, "Sony WH-1000XM5 Wireless Noise Canceling Headphones - Black"),
            sold(139.48, "Sony WH-1000XM5/L Headphones Wireless Noise Cancelling - Blue"),
            sold(149.99, "Sony WH-1000XM5SA Wireless Active Noise-Cancelling Headphones"),
            sold(134.95, "SONY WH-1000XM5 Wireless Noise Canceling Over Ear Headphones"),
            sold(145.00, "Sony WH-1000XM5 Wireless Noise Canceling Headphones - Silver"),
            sold(109.99, "Sony WH-1000XM5 Wireless Ear-Cup Wireless Headphones"),
            sold(199.99, "Sony WH-1000XM5 Wireless Noise-Canceling Headphones - Black"),
            sold(132.95, "Sony WH-1000XM5 Active Noise-Cancelling Bluetooth Headphones"),
            sold(88.99, "Sony WH-1000XM5 No Visible Serial"),
            sold(200.00, "NEW Sony WH-1000XM5 Wireless Noise Cancelling Over-Ear Headphones"),
        )

        val report = repository.buildAndSaveReport(
            searchTerm = "Sony WH-1000XM5",
            soldListings = listings,
            activeListings = 4600,
            lowestActivePrice = 219.99,
        )

        // The report computes correctly...
        assertEquals(11, report.soldCount)
        assertEquals(88.99, report.minPrice, 0.001)
        assertEquals(200.00, report.maxPrice, 0.001)
        assertTrue(report.averagePrice in 140.0..160.0)
        assertEquals(11, report.velocityLast30Days)

        // ...and it is persisted in the on-device history the UI reads.
        val history = repository.history.first()
        assertTrue(history.any { it.searchTerm == "Sony WH-1000XM5" })
    }
}
