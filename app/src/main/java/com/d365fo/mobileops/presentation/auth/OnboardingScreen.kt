package com.d365fo.mobileops.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

/**
 * Shown once, the very first time the app is opened on a device - before sign-in, since one of
 * its own pages explains what sign-in is for. Never shown again after "Get started" (or "Skip")
 * is tapped; see [OnboardingViewModel.markSeen] and the gate in AppRoot, which owns that decision
 * and is this screen's only caller - this composable itself has no ViewModel and no memory of
 * having been seen before, it just shows the pages and calls [onDone] once, from either exit.
 *
 * Four pages, not an exhaustive tour - the same "keep it to what actually matters at 6am" reasons
 * [WorkerLoginScreen] is kept to two fields. Deep how-to lives on each module's own screen; this
 * just orients a worker who has never opened the app before.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
) {
    val pages = remember {
        listOf(
            OnboardingPage(
                icon = Icons.Default.Login,
                title = "Sign in with your D365 account",
                body = "Your handheld already talks to D365FO on its own - this is the second " +
                    "step, so every scan and post is attributed to you, not just the device.",
            ),
            OnboardingPage(
                icon = Icons.Default.Widgets,
                title = "One tile per task",
                body = "The home screen lists what this handheld can do. A greyed-out tile " +
                    "marked \"Planned\" isn't broken - it just isn't built yet.",
            ),
            OnboardingPage(
                icon = Icons.Default.MoveToInbox,
                title = "Receiving: scan, don't type",
                body = "Scan a purchase order, then an item, then the quantity. Each step is " +
                    "checked against D365FO before the next one appears.",
            ),
            OnboardingPage(
                icon = Icons.Default.Checklist,
                title = "Counting: get data, then count",
                body = "Pull a counting journal's lines onto the device, scan items as you go, " +
                    "and submit only what was actually counted.",
            ),
        )
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    // safeDrawingPadding, not left to fillMaxSize alone - unlike every other top-level screen in
    // this app, there's no Scaffold here to claim system-bar insets automatically, and this
    // screen's own bottom button sits in the tightest vertical space of any screen (a full-height
    // pager above it, nothing below), so it's the one most exposed to being pushed under a
    // gesture/nav bar - confirmed live: the button was still tappable at its real position, but
    // reported zero accessibility bounds (0,0) rather than the real ones every other button in
    // this app reports, consistent with the system bar overlapping its actual layout position.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) {
                Text("Skip")
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(WarehouseStatusColors.accentGradient, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        page.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }

                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )

                Text(
                    text = page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = WarehouseStatusColors.text3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            pages.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                        .background(
                            if (index == pagerState.currentPage) {
                                WarehouseStatusColors.accent
                            } else {
                                WarehouseStatusColors.border
                            },
                            CircleShape,
                        ),
                )
            }
        }

        val isLastPage = pagerState.currentPage == pages.lastIndex
        Button(
            onClick = {
                if (isLastPage) {
                    onDone()
                } else {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(min = 56.dp),
        ) {
            Text(if (isLastPage) "Get started" else "Next")
        }
    }
}
