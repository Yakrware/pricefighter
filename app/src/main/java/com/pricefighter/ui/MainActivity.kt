package com.pricefighter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pricefighter.PriceFighterApp
import com.pricefighter.data.agent.NanoText
import com.pricefighter.data.agent.PriceCheckAgent
import com.pricefighter.ui.theme.PriceFighterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as PriceFighterApp).repository
        val nano = NanoText(this).also { it.prepare() } // warm the one-time model download
        val agent = PriceCheckAgent(repository, nano)
        setContent {
            PriceFighterTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(repository, agent))
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
