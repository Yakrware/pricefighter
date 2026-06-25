package com.pricefighter

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.pricefighter.data.db.AppDatabase
import com.pricefighter.data.ebay.CronetPageFetcher
import com.pricefighter.data.ebay.EbayClient
import com.pricefighter.data.repo.PriceCheckRepository

/**
 * Application entry point. Implements [AppFunctionConfiguration.Provider] so the OS
 * knows how to construct the classes that hold our `@AppFunction`s.
 *
 * [PriceCheckFunctions] has a no-arg constructor, so an empty configuration is
 * sufficient — the system instantiates it on demand when Gemini invokes a function.
 */
class PriceFighterApp : Application(), AppFunctionConfiguration.Provider {

    val repository: PriceCheckRepository by lazy {
        // Cronet (Chrome's network stack) so eBay's anti-bot does not 403 the fetches.
        PriceCheckRepository(
            dao = AppDatabase.get(this).historyDao(),
            ebay = EbayClient(CronetPageFetcher(this)),
        )
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(repository)
    }

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder().build()
}
