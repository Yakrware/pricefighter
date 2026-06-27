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
    fun prefersTheLongestMixedToken() {
        val text = "HEG-001 Switch OLED 64GB"
        // "HEG-001" (7) and "64GB" (4) both qualify; the longer wins.
        assertEquals("HEG-001", ProductIdentifier.extractModelNumber(text))
    }

    @Test
    fun ignoresPlainWordsAndPlainNumbers() {
        assertNull(ProductIdentifier.extractModelNumber("just some words here"))
        // Pure digits (e.g. a price or a UPC) are left to the barcode tier, not OCR.
        assertNull(ProductIdentifier.extractModelNumber("19 99 2026"))
    }
}
