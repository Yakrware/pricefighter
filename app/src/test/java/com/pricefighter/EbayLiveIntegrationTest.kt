package com.pricefighter

import com.pricefighter.data.ebay.EbayClient
import com.pricefighter.data.ebay.OkHttpPageFetcher
import com.pricefighter.data.stats.PriceStats
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end drive of the price-check pipeline against real eBay pages — the same
 * code paths the Gemini app functions call. Skipped by default (it hits the network);
 * run it explicitly with -Dpricefighter.live=true.
 */
class EbayLiveIntegrationTest {

    @Test
    fun liveSoldAndActivePriceCheck() = runBlocking {
        assumeTrue(
            "Set -Dpricefighter.live=true to run this network test.",
            System.getProperty("pricefighter.live") == "true",
        )
        val client = EbayClient(OkHttpPageFetcher())
        val term = System.getProperty("pricefighter.term") ?: "Nintendo Switch OLED"

        println("\n==== LIVE eBay price-check drive: \"$term\" ====")

        val sold = client.search(term, soldOnly = true, page = 1)
        println("[SOLD]   url    = ${sold.deeplink}")
        println("[SOLD]   total  = ${sold.totalResults}")
        println("[SOLD]   parsed = ${sold.listings.size} listings")
        sold.listings.take(6).forEachIndexed { i, l ->
            println("         #${i + 1}  ${l.currency} ${l.price}  sold=${l.soldDateIso}  ${l.title.take(58)}")
        }

        val active = client.search(term, soldOnly = false, page = 1, sortLowestPriceFirst = true)
        println("[ACTIVE] total  = ${active.totalResults}")
        println("[ACTIVE] lowest = ${active.lowestPrice}")

        if (sold.listings.isNotEmpty()) {
            val report = PriceStats.buildReport(
                searchTerm = term,
                soldListings = sold.listings,
                activeListings = active.totalResults,
                lowestActivePrice = active.lowestPrice,
            )
            println("\n---- REPORT (what buildPriceReport returns to Gemini) ----")
            println("range    : ${report.currency} ${report.minPrice} - ${report.maxPrice}")
            println("average  : ${report.currency} ${report.averagePrice}")
            println("median   : ${report.currency} ${report.medianPrice}")
            println("velocity : ${report.velocityLast30Days} sold in last 30 days (of ${report.soldCount} sampled)")
            println("active   : ${report.activeListings} listings, lowest ${report.lowestActivePrice}")
            println("deeplink : ${report.soldDeeplink}")
        } else {
            println("\n!! No sold listings parsed — eBay likely returned a captcha/empty page for this host.")
        }
        println("==== end ====\n")
    }
}
