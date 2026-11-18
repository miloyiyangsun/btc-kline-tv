import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.UiState
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.PriceChart
import com.milolab.btctv.ui.fmtPct
import com.milolab.btctv.ui.fmtPrice
import com.milolab.btctv.ui.fmtSigned
import com.milolab.btctv.ui.fmtVol as fmtVolume
import kotlin.math.max
import kotlin.math.min

/* ---------------------------------------------------------------- 方向 B：极简数字钟 */

@Composable
fun StyleMinimal(st: UiState) {
    val s = 1f
    Box(Modifier.fillMaxSize().background(Color(0xFF050506))) {
        Column(Modifier.fillMaxSize().padding((72f * s).dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((7f * s).dp).clip(CircleShape).background(C.Up))
                Spacer(Modifier.width((12f * s).dp))
                Text(
                    "BTC / USDT",
                    color = C.Dim,
                    fontSize = (16f * s).sp,
                    letterSpacing = (3f * s).sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "18:49",
                    color = C.Dimmer,
                    fontSize = (16f * s).sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    fmtPrice(st.price),
                    color = Color(0xFFF7F8FA),
                    fontSize = (132f * s).sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                Spacer(Modifier.height((6f * s).dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pct = st.quote?.changePct ?: 0.0
                    Text(
                        fmtPct(pct),
                        color = if (pct >= 0) C.Up else C.Down,
                        fontSize = (22f * s).sp,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.width((14f * s).dp))
                    Text(
                        fmtSigned(st.quote?.changeAbs ?: 0.0),
                        color = C.Dimmer,
                        fontSize = (18f * s).sp,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
                Spacer(Modifier.height((54f * s).dp))
                Sparkline(
                    values = st.candles.map { it.c },
                    up = C.Up,
                    down = C.Down,
                    modifier = Modifier.fillMaxWidth(0.62f).height((110f * s).dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TF.entries.forEach { tf ->
                    val sel = tf == st.tf
                    Text(
                        tf.label,
                        color = if (sel) Color(0xFFF7F8FA) else C.Dimmer,
                        fontSize = (14f * s).sp,
                        letterSpacing = (0.6f * s).sp,
                        modifier = Modifier.padding(horizontal = (8f * s).dp)
                    )
                }
            }
            Spacer(Modifier.height((18f * s).dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("◀ ▶ 周期     ▲ ▼ 币种     OK 设置", color = Color(0xFF33363D), fontSize = (11f * s).sp)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, up: Color, down: Color, modifier: Modifier) {
    if (values.size < 2) return
    Canvas(modifier) {
        var lo = values.min()
        var hi = values.max()
        if (hi <= lo) hi = lo + 1
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1f)
            val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, up.copy(alpha = 0.10f), style = Stroke(width = 10f, cap = StrokeCap.Round))
        drawPath(path, up, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        drawCircle(up, 4.5f, Offset(size.width, size.height - ((values.last() - lo) / (hi - lo)).toFloat() * size.height))
    }
}

/* ---------------------------------------------------------------- 方向 C：霓虹玻璃 */

@Composable
fun StyleNeon(st: UiState) {
    val s = 1f
    val accent = Color(0xFFF7931A)
    val violet = Color(0xFF7B5CFF)
    Box(Modifier.fillMaxSize().background(Color(0xFF06060B))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0918), Color(0xFF05060A)), 0f, size.height))
            drawRect(
                Brush.radialGradient(
                    listOf(violet.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.86f, size.height * 0.02f),
                    radius = size.height * 0.95f
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.10f, size.height * 0.92f),
                    radius = size.height * 0.8f
                )
            )
        }

        Column(Modifier.fillMaxSize().padding(horizontal = (54f * s).dp, vertical = (34f * s).dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassPill {
                    Box(Modifier.size((30f * s).dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Text("₿", color = accent, fontSize = (17f * s).sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width((10f * s).dp))
                    Text("BTC / USDT", color = C.Text, fontSize = (17f * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (1.2f * s).sp)
                }
                Spacer(Modifier.width((12f * s).dp))
                GlassPill {
                    Text("OKX · 实时", color = C.Up, fontSize = (13f * s).sp, letterSpacing = (1f * s).sp)
                }
                Spacer(Modifier.weight(1f))
                Text("18:49:44", color = C.Dim, fontSize = (17f * s).sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height((22f * s).dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    fmtPrice(st.price),
                    fontSize = (104f * s).sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        fontFeatureSettings = "tnum",
                        brush = Brush.verticalGradient(
                            0f to Color(0xFFFFFFFF),
                            0.62f to Color(0xFFFFE6C4),
                            1f to Color(0xFFF7931A)
                        )
                    )
                )
                Spacer(Modifier.width((20f * s).dp))
                Box(Modifier.padding(bottom = (16f * s).dp)) {
                    GlassPill(bg = C.Up.copy(alpha = 0.10f), border = C.Up.copy(alpha = 0.28f)) {
                        Text("▲ +0.74%", color = C.Up, fontSize = (17f * s).sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = (18f * s).dp)) {
                    Text(
                        "24H 高   " + fmtPrice(st.quote?.high24h ?: 0.0),
                        color = C.Dim, fontSize = (13f * s).sp, style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.height((5f * s).dp))
                    Text(
                        "24H 低   " + fmtPrice(st.quote?.low24h ?: 0.0),
                        color = C.Dim, fontSize = (13f * s).sp, style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.height((5f * s).dp))
                    Text(
                        "24H 量   " + fmtVolume(st.quote?.vol24h ?: 0.0) + " BTC",
                        color = C.Dimmer, fontSize = (13f * s).sp, style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }

            Spacer(Modifier.height((18f * s).dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape((20f * s).dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape((20f * s).dp))
                    .padding(start = (12f * s).dp, top = (10f * s).dp, bottom = (6f * s).dp, end = (2f * s).dp)
            ) {
                PriceChart(
                    candles = st.candles,
                    style = ChartStyle.AREA,
                    showVolume = false,
                    tf = st.tf,
                    accent = accent,
                    up = C.Up,
                    down = C.Down,
                    s = s,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height((16f * s).dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TF.entries.forEach { tf ->
                    val sel = tf == st.tf
                    Box(Modifier.padding(end = (6f * s).dp)) {
                        GlassPill(
                            bg = if (sel) accent.copy(alpha = 0.9f) else Color(0x12FFFFFF),
                            border = if (sel) accent else Color(0x1AFFFFFF),
                            hp = 9f,
                            vp = 6f
                        ) {
                            Text(
                                tf.label,
                                color = if (sel) Color(0xFF0A0C12) else C.Dim,
                                fontSize = (12.5f * s).sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("◀ ▶ 周期    ▲ ▼ 币种    OK 设置", color = Color(0xFF3A3F4C), fontSize = (12f * s).sp)
            }
        }
    }
}

@Composable
private fun GlassPill(
    bg: Color = Color(0x14FFFFFF),
    border: Color = Color(0x1AFFFFFF),
    hp: Float = 14f,
    vp: Float = 7f,
    content: @Composable () -> Unit
) {
    val s = 1f
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = (hp * s).dp, vertical = (vp * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
