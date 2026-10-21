package org.akanework.gramophone.ui.asmr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** Keep controls composed while their visible height follows the list's scroll and fling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AsmrCollapsingLayout(resetKey: Any?, header: @Composable () -> Unit,
                                  content: @Composable () -> Unit) {
    val state = rememberTopAppBarState()
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state)
    LaunchedEffect(resetKey) {
        state.heightOffset = 0f
        state.contentOffset = 0f
    }
    Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) {
        Column(Modifier.clipToBounds().layout { measurable, constraints ->
            val panel = measurable.measure(constraints.copy(minHeight = 0))
            // The whole control panel collapses, including the search/filter rows.
            state.heightOffsetLimit = -panel.height.toFloat()
            val clampedOffset = state.heightOffset.coerceIn(state.heightOffsetLimit, 0f)
            if (state.heightOffset != clampedOffset) state.heightOffset = clampedOffset
            val offset = clampedOffset.roundToInt()
            layout(panel.width, (panel.height + offset).coerceAtLeast(0)) {
                panel.placeRelative(0, offset)
            }
        }) { header() }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
