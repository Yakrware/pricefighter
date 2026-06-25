package com.pricefighter.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Small display-formatting helpers for the history UI. */
object Format {
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())

    fun money(amount: Double?, currencyCode: String): String {
        if (amount == null) return "—"
        return runCatching {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                currency = Currency.getInstance(currencyCode)
            }.format(amount)
        }.getOrElse { "%,.2f %s".format(amount, currencyCode) }
    }

    fun timestamp(epochMs: Long): String =
        dateFmt.format(Instant.ofEpochMilli(epochMs))
}
