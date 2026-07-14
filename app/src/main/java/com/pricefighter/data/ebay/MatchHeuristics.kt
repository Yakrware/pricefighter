package com.pricefighter.data.ebay

import com.pricefighter.data.model.EbayListing
import java.util.regex.Pattern
import kotlin.math.ceil

/**
 * Cheap, offline stand-in for the agent's judgement. In the normal flow Gemini Nano decides which
 * listings genuinely match (see `PriceCheckAgent`); this is the fallback used only when Nano isn't
 * available — it weeds out obviously-wrong listings by title token overlap and drops the empty-box /
 * parts-only / broken listings a human would never price against.
 */
object MatchHeuristics {

    // eBay's condition filter only catches listings the seller marked "For parts". Empty boxes and
    // broken units are routinely listed under New/Used with the tell in the *title*, so a keyword
    // search (and plain token overlap) keeps them. Drop them here in the offline path.
    private val JUNK_TITLE = Pattern.compile(
        "(?i)\\b(" +
            "box\\s+only|empty\\s+box|just\\s+the\\s+box|box\\s+(?:and|&|\\+)\\s+manual|" +
            "no\\s+(?:console|device|game|controller|headset|handset)|" +
            "for\\s+parts|parts\\s+only|spares?\\s*(?:or|/)\\s*repair|repair\\s+only|" +
            "not\\s+working|non[\\s-]?working|does\\s+not\\s+work|doesn'?t\\s+work|faulty|" +
            "case\\s+only|cover\\s+only|shell\\s+only|strap\\s+only|band\\s+only|manual\\s+only|" +
            "replacement\\s+(?:case|cover|shell)" +
            ")\\b",
    )

    // A combo/bundle sells the item together with other hardware (a CPU bundled with a
    // motherboard, a console bundled with games), so its price isn't the item's price. The "+ X"
    // and "w/ X" forms are deliberately symmetric — searching for the motherboard should drop a
    // "Motherboard + CPU" combo just as a CPU search drops "CPU + Motherboard".
    private val BUNDLE_TITLE = Pattern.compile(
        "(?i)(" +
            "\\bcombo\\b|\\bbundle\\b|\\bjob\\s+lot\\b|\\blot\\s+of\\s+\\d+|" +
            "\\+\\s*(?:motherboard|mobo|cpu|processor|ram|memory|gpu|graphics\\s+card|cooler|" +
            "psu|power\\s+supply|ssd|hdd)\\b|" +
            "\\bw/\\s*(?:motherboard|mobo|cpu|processor|ram|memory|cooler)\\b|" +
            "\\bwith\\s+(?:motherboard|mobo)\\b" +
            ")",
    )

    /**
     * Keep listings whose title contains at least ~60% of the search tokens and that aren't an
     * obvious empty-box / parts-only / broken listing, or a combo/bundle with other hardware.
     */
    fun byTokenOverlap(term: String, listings: List<EbayListing>): List<EbayListing> {
        val tokens = term.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val needed = ceil(tokens.size * 0.6).toInt().coerceAtLeast(1)
        return listings.filter { listing ->
            if (isJunk(listing.title) || isBundle(listing.title)) return@filter false
            if (tokens.isEmpty()) return@filter true
            val title = listing.title.lowercase()
            tokens.count { title.contains(it) } >= needed
        }
    }

    /** True for titles advertising an empty box, parts-only, or broken/non-working item. */
    fun isJunk(title: String): Boolean = JUNK_TITLE.matcher(title).find()

    /** True for combo/bundle/lot listings that package the item with other hardware. */
    fun isBundle(title: String): Boolean = BUNDLE_TITLE.matcher(title).find()
}
