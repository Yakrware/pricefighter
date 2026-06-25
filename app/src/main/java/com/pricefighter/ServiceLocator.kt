package com.pricefighter

import com.pricefighter.data.repo.PriceCheckRepository

/**
 * Process-wide handle to the repository.
 *
 * App-function classes are instantiated by the framework with a no-arg constructor,
 * so they can't receive the repository through their constructor. They read it from
 * here instead. [PriceFighterApp.onCreate] runs before any function is invoked, so
 * the repository is always initialized by the time a function executes.
 */
object ServiceLocator {
    @Volatile
    private var repo: PriceCheckRepository? = null

    fun init(repository: PriceCheckRepository) {
        repo = repository
    }

    val repository: PriceCheckRepository
        get() = requireNotNull(repo) { "ServiceLocator not initialized; PriceFighterApp.onCreate did not run." }
}
