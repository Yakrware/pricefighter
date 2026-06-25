package com.pricefighter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pricefighter.PriceFighterApp
import com.pricefighter.ui.theme.PriceFighterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as PriceFighterApp).repository
        setContent {
            PriceFighterTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(repository))
                HistoryScreen(viewModel = viewModel)
            }
        }
    }
}
