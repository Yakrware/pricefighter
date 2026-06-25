package com.pricefighter

import com.pricefighter.data.ebay.EbayParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbayParserTest {

    // A trimmed snippet using eBay's s-item / srp-controls markup.
    private val html = """
        <html><body>
          <h1 class="srp-controls__count-heading"><span>1,234</span> results for nintendo switch</h1>
          <ul>
            <li class="s-item">
              <a class="s-item__link" href="https://www.ebay.com/itm/000?_trk=abc"></a>
              <div class="s-item__title">Shop on eBay</div>
              <span class="s-item__price">${'$'}20.00</span>
            </li>
            <li class="s-item">
              <a class="s-item__link" href="https://www.ebay.com/itm/111?hash=xyz"></a>
              <div class="s-item__title">Nintendo Switch OLED White</div>
              <span class="s-item__price">${'$'}1,299.95</span>
              <span class="s-item__caption--signal POSITIVE">Sold Jun 20, 2026</span>
            </li>
            <li class="s-item">
              <a class="s-item__link" href="https://www.ebay.com/itm/222"></a>
              <div class="s-item__title">Nintendo Switch Lite</div>
              <span class="s-item__price">${'$'}150.00 to ${'$'}200.00</span>
              <span class="s-item__caption--signal POSITIVE">Sold Jun 1, 2026</span>
            </li>
          </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesTotalCount() {
        assertEquals(1234, EbayParser.parse(html).totalResults)
    }

    @Test
    fun skipsShopOnEbayPlaceholderAndParsesRealItems() {
        val listings = EbayParser.parse(html).listings
        assertEquals(2, listings.size)

        val first = listings[0]
        assertEquals("Nintendo Switch OLED White", first.title)
        assertEquals(1299.95, first.price, 0.001)
        assertEquals("USD", first.currency)
        assertEquals("2026-06-20", first.soldDateIso)
        // Tracking query params are stripped from the item URL.
        assertEquals("https://www.ebay.com/itm/111", first.itemUrl)
    }

    @Test
    fun parsesLowValueFromAPriceRange() {
        val lite = EbayParser.parse(html).listings.first { it.title.contains("Lite") }
        assertEquals(150.0, lite.price, 0.001)
    }

    // eBay's current SRP markup: <li class="s-card"> with .s-card__* children, a "New Listing"
    // badge fused into the title, and a struck-through "was" price alongside the real one.
    private val sCardHtml = """
        <html><body>
          <h1 class="srp-controls__count-heading"><span class="BOLD">6,200</span>+ results for nintendo switch oled</h1>
          <ul class="srp-results srp-list">
            <li class="s-card s-card--horizontal">
              <a class="s-card__link" href="https://www.ebay.com/itm/358702912755?_skw=x&hash=y"></a>
              <span class="s-card__title">New ListingNintendo Switch OLED White Console</span>
              <span class="su-styled-text positive strikethrough s-card__price">${'$'}249.99</span>
              <span class="su-styled-text positive bold s-card__price">${'$'}199.99</span>
              <span class="su-styled-text secondary default">Sold  Jun 23, 2026</span>
            </li>
          </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesCurrentSCardMarkup() {
        val page = EbayParser.parse(sCardHtml)
        assertEquals(6200, page.totalResults)
        assertEquals(1, page.listings.size)
        val item = page.listings[0]
        // "New Listing" badge stripped from the title.
        assertEquals("Nintendo Switch OLED White Console", item.title)
        // The real price, not the struck-through "was" price.
        assertEquals(199.99, item.price, 0.001)
        assertEquals("2026-06-23", item.soldDateIso)
        assertEquals("https://www.ebay.com/itm/358702912755", item.itemUrl)
    }
}
