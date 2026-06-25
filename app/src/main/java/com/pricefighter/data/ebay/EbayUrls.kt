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
    fun search(
        term: String,
        soldOnly: Boolean,
        page: Int = 1,
        sortLowestPriceFirst: Boolean = false,
        itemsPerPage: Int = ITEMS_PER_PAGE,
    ): String {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
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
