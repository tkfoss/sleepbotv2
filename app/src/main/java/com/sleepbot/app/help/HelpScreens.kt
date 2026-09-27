package com.sleepbot.app.help

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sleepbot.app.R
import com.sleepbot.app.ui.theme.RobotoRegular
import com.sleepbot.app.ui.theme.SB
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Help tab (ResourcesFragment + resources_new.xml)
// ---------------------------------------------------------------------------------------------

private data class HelpItem(@param:StringRes val label: Int, val file: String)
private data class HelpSection(@param:DrawableRes val banner: Int, @param:StringRes val title: Int, val items: List<HelpItem>)

private val helpSections = listOf(
    HelpSection(
        R.drawable.resources_sleepbot, R.string.help_section_sleepbot, listOf(
            HelpItem(R.string.help_instructions, "using_instructions.html"),
            HelpItem(R.string.help_entries_and_graphs, "using_entries.html"),
            HelpItem(R.string.help_support, "using_faq.html"),
        ),
    ),
    HelpSection(
        R.drawable.resources_sleep, R.string.help_section_sleep, listOf(
            HelpItem(R.string.help_eat_this, "eat_this.html"),
            HelpItem(R.string.help_read_this, "read_this.html"),
            HelpItem(R.string.help_quick_strategies, "sleep_quick_strategies.html"),
            HelpItem(R.string.help_prepare_bedroom, "prep_bedroom.html"),
        ),
    ),
    // Labels/order follow the reference screenshot (design.md §5 discrepancy note).
    HelpSection(
        R.drawable.resources_wake, R.string.help_section_wake, listOf(
            HelpItem(R.string.help_caffeine_chart, "caffeine_chart.html"),
            HelpItem(R.string.help_quick_strategies, "wake_quick_strategies.html"),
            HelpItem(R.string.help_do_this, "watch_this.html"),
            HelpItem(R.string.help_eat_this, "what_to_eat.html"),
        ),
    ),
    HelpSection(
        R.drawable.resources_learn, R.string.help_section_learn, listOf(
            HelpItem(R.string.help_sleep_debt, "sleep_debt.html"),
            HelpItem(R.string.help_health_problems, "health_risks.html"),
            HelpItem(R.string.help_sleep_disorders, "sleep_disorders.html"),
        ),
    ),
)

/** Help tab accordion; [onOpen] gets an assets/kb file name. */
@Composable
fun HelpTab(onOpen: (String) -> Unit) {
    var open by rememberSaveable { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(open) {
        if (open == helpSections.lastIndex) {
            delay(300)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bannerHeight = if (maxHeight.value.isFinite()) maxHeight / 4 - 1.dp else 88.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            helpSections.forEachIndexed { i, section ->
                if (i > 0) Spacer(Modifier.height(1.dp))
                Box(
                    Modifier.fillMaxWidth().height(bannerHeight)
                        .clickable { open = if (open == i) -1 else i },
                ) {
                    Image(
                        painterResource(section.banner), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    )
                    Text(
                        stringResource(section.title),
                        Modifier.fillMaxWidth().background(SB.HelpTitleBand).padding(10.dp),
                        color = Color.White, fontSize = 20.sp, fontFamily = RobotoRegular,
                    )
                }
                // Legacy animation duration = list height in px / density, i.e. its height in dp as ms.
                val durationMs = section.items.size * 43
                AnimatedVisibility(
                    visible = open == i,
                    enter = expandVertically(tween(durationMs)),
                    exit = shrinkVertically(tween(durationMs)),
                ) {
                    Column(Modifier.fillMaxWidth().background(SB.MainBg)) {
                        section.items.forEachIndexed { j, item ->
                            if (j > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White))
                            Text(
                                stringResource(item.label),
                                Modifier.fillMaxWidth().clickable { onOpen(item.file) }.padding(10.dp),
                                color = Color.White, fontSize = 16.sp, fontFamily = RobotoRegular,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Raw HTML viewer (RawHTMLActivity)
// ---------------------------------------------------------------------------------------------

private const val KB_BASE = "file:///android_asset/kb/"

/** Full-screen WebView of assets/kb/[file] on black; back navigates WebView history, then [onBack]. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlScreen(file: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val html = remember(file) {
        runCatching { context.assets.open("kb/$file").bufferedReader().use { it.readText() } }
            .getOrElse { "<html><body style=\"color:#fff\">Page not found.</body></html>" }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url
                            if (url.scheme == "file") return false
                            // External links (Play Store, social, mailto, web) open outside the app.
                            return try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                true
                            } catch (_: ActivityNotFoundException) {
                                url.scheme !in setOf("http", "https")
                            }
                        }
                    }
                    loadDataWithBaseURL(KB_BASE, html, "text/html", "utf-8", null)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// First-run tutorial overlay (tutorial_views + tutorial_1..4.xml)
// ---------------------------------------------------------------------------------------------

private val TutorialBg = Color(0xCC000000)
private val IndicatorSelected = Color(0xFF33B5E5)
private val IndicatorUnselected = Color(0xFFBBBBBB)

/** First-run 4-page tutorial overlay. */
@Composable
fun TutorialOverlay(onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 4 })
    Box(
        Modifier.fillMaxSize()
            // Swallow taps so nothing underneath reacts while the overlay is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize().background(TutorialBg)) { page ->
            when (page) {
                0 -> TutorialPage1()
                1 -> TutorialPage2()
                2 -> TutorialPage3()
                else -> TutorialPage4(onFinish)
            }
        }
        // LinePageIndicator: 12dp tall with 10dp bottom padding, lines 30dp x 4dp, 4dp gap, centered.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(4) { i ->
                Canvas(Modifier.width(30.dp).height(4.dp)) {
                    drawLine(
                        if (pager.currentPage == i) IndicatorSelected else IndicatorUnselected,
                        Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
                    )
                }
            }
        }
    }
}

@Composable
private fun TText(@StringRes id: Int, size: TextUnit, modifier: Modifier = Modifier, center: Boolean = true) {
    Text(
        stringResource(id), modifier, color = Color.White, fontSize = size,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
private fun Img(@DrawableRes id: Int, modifier: Modifier = Modifier) {
    Image(painterResource(id), null, modifier)
}

@Composable
private fun TutorialPage1() {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column {
            Img(R.drawable.intro_alarm)
            Img(R.drawable.intro_1arrow, Modifier.padding(start = 10.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 60.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TText(R.string.tutorial_title_1, 25.sp, center = false)
            TText(R.string.tutorial_1_1, 14.sp, Modifier.padding(top = 44.dp), center = false)
            TText(R.string.tutorial_1_2, 14.sp, Modifier.padding(top = 44.dp), center = false)
            Img(R.drawable.intro_1, Modifier.padding(top = 15.dp))
        }
    }
}

@Composable
private fun TutorialPage2() {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 200.dp, bottom = 30.dp)) {
        Img(R.drawable.intro_2arrow, Modifier.padding(top = 20.dp))
        Column(Modifier.fillMaxWidth().padding(start = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TText(R.string.tutorial_2_1, 15.sp, Modifier.padding(horizontal = 20.dp))
            Img(R.drawable.intro_2, Modifier.padding(top = 34.dp))
        }
    }
}

@Composable
private fun TutorialPage3() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 30.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TText(R.string.tutorial_3_1, 16.sp, Modifier.padding(horizontal = 20.dp))
        Img(R.drawable.intro_3, Modifier.padding(top = 10.dp))
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Img(R.drawable.intro_3circle, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            Img(R.drawable.intro_3arrow, Modifier.align(Alignment.TopEnd).padding(top = 35.dp, end = 7.dp))
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TText(R.string.tutorial_3_2, 14.sp)
                TText(R.string.tutorial_3_3, 15.sp, Modifier.padding(top = 35.dp))
            }
        }
    }
}

@Composable
private fun TutorialPage4(onFinish: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 38.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Img(R.drawable.intro_4icons, Modifier.padding(top = 10.dp))
        TText(R.string.tutorial_4_1, 16.sp, Modifier.padding(top = 15.dp).padding(horizontal = 20.dp))
        Img(R.drawable.intro_4, Modifier.padding(top = 20.dp))
        TText(R.string.tutorial_4_2, 15.sp, Modifier.padding(top = 25.dp).padding(horizontal = 20.dp))
        // Holo-dark style button.
        Box(
            Modifier.padding(top = 25.dp).wrapContentWidth()
                .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                .background(Color(0xFF4A4A4A), RoundedCornerShape(2.dp))
                .clickable(onClick = onFinish)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.get_started), color = Color.White, fontSize = 18.sp)
        }
    }
}
