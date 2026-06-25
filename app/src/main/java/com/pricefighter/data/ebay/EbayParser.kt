package com.pricefighter.data.ebay

import com.pricefighter.data.model.EbayListing
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

/** Result of parsing one eBay search-results HTML page. */
data class ParsedPage(
    val totalResults: Int,
    val listings: List<EbayListing>,
)

/**
 * Parses eBay search-results HTML into structured listings.
 *
 * eBay markup changes over time, so every selector has fallbacks and each item is
 * parsed defensively — a single malformed card is skipped rather than failing the page.
 */
object EbayParser {
    private val FIRST_NUMBER = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)")
    private val SOLD_DATE = Pattern.compile("Sold\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4})")
    private val DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html)
        return ParsedPage(parseTotal(doc), parseListings(doc))
    }

    private fun parseTotal(doc: Document): Int {
        val heading = doc.selectFirst("h1.srp-controls__count-heading")
            ?: doc.selectFirst(".srp-controls__count-heading")
            ?: doc.selectFirst(".result-count__count-heading")
            ?: return -1
        val digits = heading.text().replace(",", "")
        val m = FIRST_NUMBER.matcher(digits)
        return if (m.find()) m.group(1)?.substringBefore('.')?.toIntOrNull() ?: -1 else -1
    }

    private fun parseListings(doc: Document): List<EbayListing> {
        val items = doc.select("li.s-item")
            .ifEmpty { doc.select("li.s-card") }
            .ifEmpty { doc.select(".s-item__wrapper") }
        val out = ArrayList<EbayListing>(items.size)
        for (el in items) {
            runCatching { parseItem(el) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun parseItem(el: Element): EbayListing? {
        val rawTitle = (el.selectFirst(".s-item__title") ?: el.selectFirst(".s-card__title"))
            ?.text()?.trim()
            ?: return null
        // eBay prepends a "New Listing" badge to the title text on recent listings.
        val title = if (rawTitle.startsWith("New Listing", ignoreCase = true)) {
            rawTitle.substring("New Listing".length).trim()
        } else {
            rawTitle
        }
        // The first card is frequently an "Shop on eBay" placeholder.
        if (title.isBlank() || title.equals("Shop on eBay", ignoreCase = true)) return null

        // Prefer the actual price; skip struck-through "was" prices in s-card markup.
        val priceEl = el.selectFirst(".s-item__price")
            ?: el.select(".s-card__price").firstOrNull { "strikethrough" !in it.className() }
            ?: el.selectFirst(".s-card__price")
        val priceText = priceEl?.text() ?: return null
        val price = parsePrice(priceText) ?: return null

        val url = (el.selectFirst("a.s-item__link") ?: el.selectFirst("a.s-card__link") ?: el.selectFirst("a[href]"))
            ?.attr("href")
            ?.let(::cleanUrl)
            ?: ""

        return EbayListing(
            title = title,
            price = price,
            currency = parseCurrency(priceText),
            soldDateIso = parseSoldDate(el),
            itemUrl = url,
        )
    }

    /** Handles "$123.45" and ranges like "$100.00 to $200.00" (takes the first/low value). */
    private fun parsePrice(text: String): Double? {
        val m = FIRST_NUMBER.matcher(text.replace(",", ""))
        return if (m.find()) m.group(1)?.toDoubleOrNull() else null
    }

    private fun parseCurrency(text: String): String = when {
        text.contains("$") -> "USD"
        text.contains("£") -> "GBP"
        text.contains("€") -> "EUR"
        text.contains("CAD", ignoreCase = true) -> "CAD"
        text.contains("AUD", ignoreCase = true) -> "AUD"
        else -> "USD"
    }

    private fun parseSoldDate(el: Element): String? {
        val m = SOLD_DATE.matcher(el.text())
        if (!m.find()) return null
        return runCatching { LocalDate.parse(m.group(1), DATE_FMT).toString() }.getOrNull()
    }

    private fun cleanUrl(url: String): String {
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }
}
