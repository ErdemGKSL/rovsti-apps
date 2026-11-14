package dev.rosty.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.rosty.design.media.LocalRostyImageLoader
import dev.rosty.design.theme.RostyTheme

/** Debug-only demo app: one page per :design component (grows with every E1 story). */
class CatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before setContent so the bars are transparent and the content draws behind them.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val loader = remember { catalogImageLoader(applicationContext) }
            CompositionLocalProvider(LocalRostyImageLoader provides loader) {
                RostyTheme {
                    CatalogApp()
                }
            }
        }
    }
}
