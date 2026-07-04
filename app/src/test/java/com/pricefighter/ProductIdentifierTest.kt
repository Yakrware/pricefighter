package com.pricefighter

import com.pricefighter.data.vision.ProductIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductIdentifierTest {

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
