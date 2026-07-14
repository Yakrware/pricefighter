package com.pricefighter

import com.pricefighter.data.vision.Confidence
import com.pricefighter.data.vision.ProductIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductIdentifierTest {

    @Test
    fun parsesRankedBrandModelCandidates() {
        val raw = """
            Sony | WH-1000XM5 | high
            Sony | WH-1000XM4 | medium
            Bose | QuietComfort 45 | low
        """.trimIndent()
        val candidates = ProductIdentifier.parseCandidates(raw)

        assertEquals(3, candidates.size)
        assertEquals("Sony", candidates[0].brand)
        assertEquals("WH-1000XM5", candidates[0].model)
        assertEquals("Sony WH-1000XM5", candidates[0].searchTerm)
        assertEquals(Confidence.HIGH, candidates[0].confidence)
        assertEquals(Confidence.MEDIUM, candidates[1].confidence)
        assertEquals(Confidence.LOW, candidates[2].confidence)
        assertEquals("Bose QuietComfort 45", candidates[2].searchTerm)
    }

    @Test
    fun toleratesNanoListFormattingAndJunkLines() {
        val raw = """
            Here are my guesses:
            1. Sony | WH-1000XM5 | high
            - Sony | WH-1000XM4 | medium
        """.trimIndent()
        val candidates = ProductIdentifier.parseCandidates(raw)

        // The prose line has no "|" and is skipped; numbering/bullets are stripped from the brand.
        assertEquals(2, candidates.size)
        assertEquals("Sony", candidates[0].brand)
        assertEquals("Sony WH-1000XM5", candidates[0].searchTerm)
        assertEquals("Sony WH-1000XM4", candidates[1].searchTerm)
    }

    @Test
    fun returnsNoCandidatesWhenNanoIsUnsure() {
        assertTrue(ProductIdentifier.parseCandidates("unknown").isEmpty())
        assertTrue(ProductIdentifier.parseCandidates("").isEmpty())
        // A line with no separator isn't a guess we can trust.
        assertTrue(ProductIdentifier.parseCandidates("probably some headphones").isEmpty())
    }

    @Test
    fun buildsASearchTermFromWhicheverPartIsKnown() {
        val candidates = ProductIdentifier.parseCandidates("unknown | WH-1000XM5 | medium")
        assertEquals(1, candidates.size)
        assertNull(candidates[0].brand)
        assertEquals("WH-1000XM5", candidates[0].searchTerm)
    }

    @Test
    fun pullsTheModelNumberOutOfLabelText() {
        val text = "Sony Wireless Headphones\nModel WH-1000XM5\nMade in Malaysia"
        assertEquals("WH-1000XM5", ProductIdentifier.extractLabeledId(text))
    }

    @Test
    fun readsCommonLabelVariants() {
        assertEquals("A2338", ProductIdentifier.extractLabeledId("Apple\nModel No.: A2338\nAssembled in China"))
        assertEquals("HEG-001", ProductIdentifier.extractLabeledId("Nintendo Switch OLED\nM/N HEG-001"))
        assertEquals("C2G9F3K1", ProductIdentifier.extractLabeledId("Camera\nP/N C2G9F3K1"))
    }

    @Test
    fun usesALabeledSerialWhenSureItIsOne() {
        // A serial we're certain about (explicitly labeled) is a real identifier — keep it.
        assertEquals("X7F92KQ4L", ProductIdentifier.extractLabeledId("On the back\nS/N: X7F92KQ4L"))
        assertEquals("123456789", ProductIdentifier.extractLabeledId("Serial No. 123456789"))
    }

    @Test
    fun prefersAModelNumberOverASerial() {
        val text = "Model WH-1000XM5\nSerial No. 4820193055"
        assertEquals("WH-1000XM5", ProductIdentifier.extractLabeledId(text))
    }

    @Test
    fun doesNotGrabUnlabeledTokens() {
        // The old heuristic pulled the longest letter+digit token off the item; an unlabeled code
        // could be anything, so without a label we never pull it.
        assertNull(ProductIdentifier.extractLabeledId("HEG-001 Switch OLED 64GB"))
        assertNull(ProductIdentifier.extractLabeledId("X7F92KQ4L stamped on the back"))
    }

    @Test
    fun ignoresPlainWordsAndPlainNumbers() {
        assertNull(ProductIdentifier.extractLabeledId("just some words here"))
        // Pure digits with no label (e.g. a price or a UPC) are left to the barcode tier, not OCR.
        assertNull(ProductIdentifier.extractLabeledId("19 99 2026"))
        // A label with no identifier-shaped token nearby stays null.
        assertNull(ProductIdentifier.extractLabeledId("Model: Deluxe Edition"))
    }
}
