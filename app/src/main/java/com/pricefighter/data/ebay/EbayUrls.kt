package com.pricefighter.data.ebay

import java.net.URLEncoder

/** Builds public eBay search URLs (the same pages a browser would load). */
object EbayUrls {
    private const val BASE = "https://www.ebay.com/sch/i.html"
    const val ITEMS_PER_PAGE = 60

    // eBay also serves 240 items/page. Used for the 30-day window scan so it spans the
    // window in a few requests instead of ~15 pages of 60.
    const val WINDOW_ITEMS_PER_PAGE = 240

    // eBay _sop sort codes.
    private const val SOP_BEST_MATCH = 12
    private const val SOP_ENDED_RECENT = 13
    private const val SOP_PRICE_LOWEST_FIRST = 15

    // Item-condition filter (LH_ItemCondition): include every sellable condition — New
    // (1000), Open Box (1500), New other (1750), all refurbished grades (2000/2010/2020/
    // 2030/2500), and Used (3000) — while excluding "For parts or not working" (7000), so
    // parts-only listings are dropped. "%7C" is an encoded "|" separating the codes.
    private const val CONDITIONS_EXCLUDING_PARTS =
        "1000%7C1500%7C1750%7C2000%7C2010%7C2020%7C2030%7C2500%7C3000"

    /**
     * @param soldOnly adds the LH_Sold=1 & LH_Complete=1 filters (completed/sold listings).
     * @param sortLowestPriceFirst sorts ascending by price+shipping (used to find the lowest active price).
     */
    // A spec token like "128GB" / "1080p" / "13in" — a number glued to a unit. It has letters and
    // digits like a model number, but quoting it would wrongly reject listings that space it out
    // ("128 GB"), so it is left as a loose keyword.
    private val SPEC_TOKEN = Regex(
        "(?i)^\\d+(?:\\.\\d+)?(gb|tb|mb|kb|in|inch|cm|mm|hz|khz|mhz|ghz|w|kw|v|mah|wh|k|p|ft|oz|lb|g|kg|ml|l)$",
    )

    /**
     * A confident model-number token: letters **and** digits, 4+ chars, only alphanumerics and
     * dashes/slashes (e.g. "WH-1000XM5", "A2338", "HEG-001"), and not a spec like "128GB".
     */
    private fun looksLikeModelNumber(token: String): Boolean =
        token.length >= 4 &&
            token.any(Char::isLetter) &&
            token.any(Char::isDigit) &&
            token.all { it.isLetterOrDigit() || it == '-' || it == '/' } &&
            !SPEC_TOKEN.matches(token)

    /**
     * Wraps any confident model-number token in double quotes so eBay phrase-matches it exactly
     * instead of returning loose "close" keyword matches. Other words are left untouched, and an
     * already-quoted token is left as-is. E.g. `Sony WH-1000XM5` → `Sony "WH-1000XM5"`.
     */
    fun quoteModelNumbers(term: String): String =
        term.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { token ->
            if (token.startsWith("\"") || !looksLikeModelNumber(token)) token else "\"$token\""
        }

    fun search(
        term: String,
        soldOnly: Boolean,
        page: Int = 1,
        sortLowestPriceFirst: Boolean = false,
        itemsPerPage: Int = ITEMS_PER_PAGE,
    ): String {
        val q = URLEncoder.encode(quoteModelNumbers(term), "UTF-8")
        val sb = StringBuilder(BASE)
        sb.append("?_nkw=").append(q)
        sb.append("&_sacat=0")
        sb.append("&_ipg=").append(itemsPerPage)
        if (soldOnly) sb.append("&LH_Sold=1&LH_Complete=1")
        sb.append("&LH_ItemCondition=").append(CONDITIONS_EXCLUDING_PARTS)
        val sop = when {
            sortLowestPriceFirst -> SOP_PRICE_LOWEST_FIRST
            soldOnly -> SOP_ENDED_RECENT
            else -> SOP_BEST_MATCH
        }
        sb.append("&_sop=").append(sop)
        if (page > 1) sb.append("&_pgn=").append(page)
        return sb.toString()
    }

    /** Public deeplink to the sold/completed results for [term] (page 1, most recent first). */
    fun soldDeeplink(term: String): String = search(term, soldOnly = true, page = 1)
}
