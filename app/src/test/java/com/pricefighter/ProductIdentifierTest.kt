package com.pricefighter

import com.pricefighter.data.vision.ProductIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductIdentifierTest {

    @Test
    fun pullsTheModelNumberOutOfLabelText() {
        val text = "Sony Wireless Headphones\nModel WH-1000XM5\nMade in Malaysia"
        assertEquals("WH-1000XM5", ProductIdentifier.extractModelNumber(text))
    }

    @Test
    fun readsCommonLabelVariants() {
        assertEquals("A2338", ProductIdentifier.extractModelNumber("Apple\nModel No.: A2338\nAssembled in China"))
        assertEquals("HEG-001", ProductIdentifier.extractModelNumber("Nintendo Switch OLED\nM/N HEG-001"))
    }

    @Test
    fun doesNotGrabUnlabeledTokens() {
        // The old heuristic pulled the longest letter+digit token off the item; a random code or
        // serial makes a useless search, so unlabeled tokens are now ignored entirely.
        assertNull(ProductIdentifier.extractModelNumber("HEG-001 Switch OLED 64GB"))
        assertNull(ProductIdentifier.extractModelNumber("SN: X7F92KQ4L on the back"))
    }

    @Test
    fun ignoresPlainWordsAndPlainNumbers() {
        assertNull(ProductIdentifier.extractModelNumber("just some words here"))
        // Pure digits (e.g. a price or a UPC) are left to the barcode tier, not OCR.
        assertNull(ProductIdentifier.extractModelNumber("19 99 2026"))
        // A label with no model-number-shaped token nearby stays null.
        assertNull(ProductIdentifier.extractModelNumber("Model: Deluxe Edition"))
    }
}
