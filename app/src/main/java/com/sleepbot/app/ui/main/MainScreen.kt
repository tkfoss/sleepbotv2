package com.sleepbot.app.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.help.HelpTab
import com.sleepbot.app.help.TutorialOverlay
import com.sleepbot.app.ui.entries.EntriesTab
import com.sleepbot.app.ui.common.drawableBackground
import com.sleepbot.app.ui.home.HomeTab
import com.sleepbot.app.ui.home.WakeDialog
import com.sleepbot.app.ui.overview.OverviewTab
import com.sleepbot.app.ui.theme.SB
import kotlinx.coroutines.launch

private data class Tab(@param:DrawableRes val on: Int, @param:DrawableRes val off: Int)

private val tabs = listOf(
    Tab(R.drawable.home_selected, R.drawable.home_unselected),
    Tab(R.drawable.entries_selected, R.drawable.entries_unselected),
    Tab(R.drawable.graphs_selected, R.drawable.graphs_unselected),
    Tab(R.drawable.info_selected, R.drawable.info_unselected),
)

@Composable
fun MainScreen(
    onOpenAlarms: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenGraphs: () -> Unit,
    onOpenHtml: (String) -> Unit,
) {
    val prefs = LocalContext.current.app.prefs
    var showTutorial by remember { mutableStateOf(!prefs.tutorialSeen) }
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(SB.MainBg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Header(onOpenAlarms, onOpenSettings)
            Row(Modifier.fillMaxWidth().height(48.dp).background(SB.TabStrip)) {
                tabs.forEachIndexed { i, t ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painterResource(if (pager.currentPage == i) t.on else t.off), null, Modifier.size(27.dp))
                    }
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> HomeTab(onOpenAlarms = onOpenAlarms, onOpenEntry = onOpenEntry)
                    1 -> EntriesTab(onOpenEntry = onOpenEntry)
                    2 -> OverviewTab(onOpenGraphs = onOpenGraphs)
                    else -> HelpTab(onOpen = onOpenHtml)
                }
            }
        }
        val wakeId by LocalContext.current.app.session.pendingWakeDialog.collectAsState()
        wakeId?.let { id ->
            LaunchedEffect(id) { pager.scrollToPage(0) }
            val session = LocalContext.current.app.session
            WakeDialog(id, onDismiss = { session.pendingWakeDialog.value = null }, onEdit = onOpenEntry)
        }
        if (showTutorial) TutorialOverlay(onFinish = { prefs.tutorialSeen = true; showTutorial = false })
    }
}

@Composable
private fun Header(onOpenAlarms: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.fillMaxSize().drawableBackground(R.drawable.namebar))
        Image(painterResource(R.drawable.sleepbot_logo), stringResource(R.string.cd_logo), Modifier.align(Alignment.Center).height(48.dp))
        PressableImage(R.drawable.alarm_unselected, R.drawable.alarm_selected, stringResource(R.string.alarms), onOpenAlarms,
            Modifier.align(Alignment.CenterStart).width(56.dp).height(48.dp))
        PressableImage(R.drawable.settings_unselected, R.drawable.settings_selected, stringResource(R.string.settings), onOpenSettings,
            Modifier.align(Alignment.CenterEnd).width(56.dp).height(48.dp))
    }
}

/** An image that swaps to its `_selected` artwork while pressed (legacy state-list drawables). */
@Composable
fun PressableImage(
    @DrawableRes normal: Int,
    @DrawableRes pressed: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    Image(
        painterResource(if (isPressed) pressed else normal),
        contentDescription,
        modifier.clickable(interactionSource = source, indication = null, onClick = onClick),
        contentScale = contentScale,
    )
}
