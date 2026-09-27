package com.sleepbot.app.alarm.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepbot.app.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Draws a (nine-patch) drawable resource stretched to the element bounds. */
@Composable
fun Modifier.drawableBackground(@DrawableRes id: Int): Modifier {
    val ctx = LocalContext.current
    val d = remember(id) { ContextCompat.getDrawable(ctx, id)!!.mutate() }
    return drawBehind {
        drawIntoCanvas { c ->
            d.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            d.draw(c.nativeCanvas)
        }
    }
}

private const val PERCENT_REQUIRED = 0.72f
/** android DecelerateInterpolator(1.0). */
private val DecelerateEasing = Easing { 1f - (1f - it) * (1f - it) }

/**
 * Legacy angrydoughnuts Slider: tray "SWIPE TO DISMISS" on slider_background with a draggable
 * dot (slider_icon on slider_btn). Drag must start on the dot; completes once the dot's center
 * passes 72% of the width (fades out 200 ms, then [onComplete]); otherwise, or if the finger
 * leaves the dot's vertical bounds, the dot slides home (200 ms decelerate).
 */
@Composable
fun DismissSlider(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val dotX = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.alpha(alpha.value)) {
        val widthPx = constraints.maxWidth.toFloat()
        val dotW = with(density) { 69.dp.toPx() }
        val doneFlag = remember { booleanArrayOf(false) }
        fun complete() {
            if (doneFlag[0]) return
            doneFlag[0] = true
            scope.launch {
                alpha.animateTo(0f, tween(200))
                onComplete()
            }
        }
        Box(
            Modifier.fillMaxSize().drawableBackground(R.drawable.slider_background),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.dismiss_slider), fontSize = 20.sp, fontStyle = FontStyle.Italic, color = Color.White)
        }
        Box(
            Modifier
                .offset { IntOffset(dotX.value.roundToInt(), 0) }
                .width(69.dp)
                .fillMaxHeight()
                .drawableBackground(R.drawable.slider_btn)
                .padding(start = 20.dp, top = 7.dp, end = 17.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.slider_icon), null, Modifier.size(32.dp))
        }
        Box(
            Modifier.fillMaxSize().pointerInput(widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val x0 = dotX.value
                    if (down.position.x < x0 || down.position.x > x0 + dotW) return@awaitEachGesture
                    down.consume()
                    val h = size.height
                    while (true) {
                        val ev = awaitPointerEvent()
                        val p = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) {
                            if ((dotX.value + dotW / 2) / widthPx > PERCENT_REQUIRED) complete()
                            else scope.launch { dotX.animateTo(0f, tween(200, easing = DecelerateEasing)) }
                            break
                        }
                        if (p.positionChange() != androidx.compose.ui.geometry.Offset.Zero) p.consume()
                        val nx = (p.position.x - dotW / 2).coerceIn(0f, widthPx - dotW)
                        scope.launch { dotX.snapTo(nx) }
                        if ((nx + dotW / 2) / widthPx > PERCENT_REQUIRED) { complete(); break }
                        if (p.position.y < 0 || p.position.y > h) {
                            scope.launch { dotX.animateTo(0f, tween(200, easing = DecelerateEasing)) }
                            break
                        }
                    }
                }
            },
        )
    }
}
