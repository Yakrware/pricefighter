package com.pricefighter.data.agent

import android.util.Log
import com.pricefighter.data.ebay.MatchHeuristics
import com.pricefighter.data.model.EbayListing
import com.pricefighter.data.model.PriceReport
import com.pricefighter.data.repo.PriceCheckRepository

/** What the agent produced, plus how it got there (for user feedback). */
data class AgentOutcome(
    val report: PriceReport,
    val query: String,
    /** True when Gemini Nano judged the matches; false when we fell back to keyword overlap. */
    val judgedByNano: Boolean,
)

/**
 * On-device price-check agent — the in-app stand-in for what we hoped consumer Gemini would do via
 * app functions (which is EAP-gated). Given a spoken or typed request it:
 *
 *  1. **Understands** — Nano distils the request into an eBay search term ("price check my old
 *     macbook pro m1" → "MacBook Pro M1").
 *  2. **Looks up** — the repository scrapes recent sold + active listings from eBay.
 *  3. **Discards poor matches** — Nano judges each distinct title, dropping accessories, parts, and
 *     wrong models; far sharper than token overlap.
 *  4. **Reports** — the surviving sold listings become a saved price report in History.
 *
 * Every Nano step fails soft to an offline heuristic, so the agent still works (just less precisely)
 * on devices without Nano or when it's mid-download.
 */
class PriceCheckAgent(
    private val repository: PriceCheckRepository,
    private val nano: NanoText,
) {
    /** Emits a short human-readable step for the UI as the agent progresses. */
    fun interface Progress {
        fun onStep(message: String)
    }

    suspend fun run(spokenOrTyped: String, progress: Progress): AgentOutcome {
        require(spokenOrTyped.isNotBlank()) { "Say or type what to price-check." }

        progress.onStep("Understanding your request…")
        val query = deriveQuery(spokenOrTyped)
        require(query.isNotBlank()) { "Couldn’t tell what to price-check — try naming the item and model." }

        progress.onStep("Searching eBay for “$query”…")
        // A single page of the most recent sold listings — a sample small enough for Nano to judge
        // title by title, yet large enough for a representative price picture.
        val sold = repository.searchSold(query, page = 1).listings
        if (sold.isEmpty()) error("No sold matches found for “$query” — try the exact brand and model.")
        val active = repository.searchActive(query)

        progress.onStep("Sorting good matches from bad…")
        val (matched, judgedByNano) = discardPoorMatches(query, sold)

        progress.onStep("Crunching the numbers…")
        val report = repository.buildAndSaveReport(
            searchTerm = query,
            soldListings = matched.ifEmpty { sold },
            activeListings = active.totalResults,
            lowestActivePrice = active.lowestPrice,
        )
        return AgentOutcome(report, query, judgedByNano)
    }

    /** Nano turns a loose request into a clean search term; falls back to light cleanup of the input. */
    private suspend fun deriveQuery(spokenOrTyped: String): String {
        val answer = nano.complete(
            "Turn this shopping request into a concise eBay search query — just the product, brand, " +
                "model and key specs, no extra words, no quotes.\n" +
                "Request: \"$spokenOrTyped\"\n" +
                "Search query:",
            maxOutputTokens = 32,
        )
        val cleaned = answer?.let(::cleanQueryLine)?.takeIf { it.isNotBlank() }
        return cleaned ?: stripFillerWords(spokenOrTyped)
    }

    /**
     * Ask Nano which distinct titles are genuinely the product, then keep every listing with an
     * approved title. Falls back to token overlap when Nano is unavailable, declines, or the sample
     * has too many distinct titles to fit a single small-model prompt.
     */
    private suspend fun discardPoorMatches(
        query: String,
        sold: List<EbayListing>,
    ): Pair<List<EbayListing>, Boolean> {
        val distinctTitles = sold.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinctTitles.size > NANO_TITLE_CAP) {
            return MatchHeuristics.byTokenOverlap(query, sold) to false
        }

        val numbered = distinctTitles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val answer = nano.complete(
            "A shopper is pricing \"$query\" on eBay. From the numbered listing titles below, reply " +
                "with ONLY the numbers that are genuinely this product. Exclude accessories, cases, " +
                "screen protectors, chargers, cables, parts, and different models. Comma-separated " +
                "numbers only.\n$numbered\nKeep:",
            maxOutputTokens = 200,
        ) ?: return MatchHeuristics.byTokenOverlap(query, sold) to false

        val keptTitles = Regex("\\d+").findAll(answer)
            .mapNotNull { it.value.toIntOrNull() }
            .map { it - 1 }
            .filter { it in distinctTitles.indices }
            .map { distinctTitles[it] }
            .toSet()

        if (keptTitles.isEmpty()) {
            Log.i(TAG, "Nano returned no usable matches for \"$query\" — falling back to heuristic")
            return MatchHeuristics.byTokenOverlap(query, sold) to false
        }
        return sold.filter { it.title.trim() in keptTitles } to true
    }

    private companion object {
        private const val TAG = "PriceFighter"

        // A page is ~60 listings; distinct titles are usually well under this. Beyond it, the prompt
        // gets long and the tiny model unreliable, so we drop to the offline filter instead.
        private const val NANO_TITLE_CAP = 60

        /** Nano can be chatty; take the first non-empty line and strip surrounding quotes/punctuation. */
        private fun cleanQueryLine(raw: String): String =
            raw.lineSequence()
                .map { it.trim().trim('"', '\'', '.', ',', ':', '-', ' ') }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

        /** Offline fallback: drop the "price check" wake phrase and leading filler from the raw input. */
        private fun stripFillerWords(input: String): String =
            input.trim()
                .replace(Regex("^(hey |ok |okay )?(google|gemini)[,! ]*", RegexOption.IGNORE_CASE), "")
                .replace(
                    Regex("^price[ -]?check(ing)?( me| us)?( the| a| an| my| some)?\\s+", RegexOption.IGNORE_CASE),
                    "",
                )
                .replace(Regex("^(whats?|what is|what's) the price (of|for)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
    }
}
