package com.sleepbot.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepbot.app.R
import com.sleepbot.app.ui.theme.SB

/** Draws a (nine-patch) drawable resource stretched behind the content, like a View background. */
@Composable
fun Modifier.drawableBackground(@DrawableRes res: Int): Modifier {
    val context = LocalContext.current
    val d = remember(res) { ContextCompat.getDrawable(context, res)!! }
    return drawBehind {
        drawIntoCanvas { c ->
            d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            d.draw(c.nativeCanvas)
        }
    }
}

/** Legacy `date_button` style: white text over the blue spinner underline 9-patch. */
@Composable
fun DateButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fontSize: TextUnit = 18.sp, width: Dp = 92.dp) {
    Box(
        modifier.width(width).height(40.dp).drawableBackground(R.drawable.spinner_bg).clickable(onClick = onClick)
            .padding(top = 2.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = fontSize, maxLines = 1)
    }
}

data class BarAction(@param:DrawableRes val icon: Int, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** 2dp divider + 48dp action bar with 1dp×24dp separators (entries / editor bottom bars). */
@Composable
fun BottomActionBar(actions: List<BarAction>, iconGap: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(SB.Divider))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.width(1.dp).height(24.dp).background(SB.Divider))
                Row(
                    Modifier.weight(1f).fillMaxHeight().clickable(enabled = a.enabled, onClick = a.onClick),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(a.icon), null, Modifier.size(24.dp))
                    Spacer(Modifier.width(iconGap))
                    Text(a.label, color = if (a.enabled) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 5-star rating bar using the original star artwork (42dp stars). */
@Composable
fun StarRating(rating: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, starSize: Dp = 42.dp) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        for (i in 1..5) {
            Image(
                painterResource(if (i <= rating) R.drawable.ratingstar_selected else R.drawable.ratingstar_unselected),
                "$i",
                Modifier.size(starSize).clickable { onChange(if (rating == i) 0 else i) },
            )
        }
    }
}

@Composable
fun CellText(text: String, modifier: Modifier = Modifier, color: Color = Color.White, bold: Boolean = false, align: TextAlign = TextAlign.Center, size: TextUnit = 17.sp) {
    Text(text, modifier, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else null, textAlign = align, maxLines = 1, softWrap = false)
}
