package ch.widmer.airview.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ch.widmer.airview.MainActivity
import ch.widmer.airview.data.DaySummary
import ch.widmer.airview.data.Goals
import ch.widmer.airview.data.WidgetState
import java.time.LocalDate
import java.util.Locale

/** Farben wie die Nachtseite der App, damit das Widget auf jedem Hintergrund ruhig wirkt. */
private val BG = Color(0xFF171C22)
private val INK = Color(0xFFE3EAEE)
private val MUTED = Color(0xFF8397A3)
private val MOVE = 0xFF7C93FF.toInt()
private val KCAL = 0xFFF2B04B.toInt()
private val HEART = 0xFFFF5C7D.toInt()

/** Drei Ringe als Bitmap (Glance kann keine Bögen zeichnen). */
private fun ringsBitmap(px: Int, fr: List<Float>): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val sw = px * 0.11f; val gap = sw * 0.3f
    listOf(MOVE, KCAL, HEART).forEachIndexed { i, c ->
        val inset = sw / 2 + i * (sw + gap)
        val r = RectF(inset, inset, px - inset, px - inset)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = c; alpha = 50 }
        cv.drawArc(r, 0f, 360f, false, track)
        val f = fr[i].coerceIn(0f, 1f)
        if (f > 0.003f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = c; strokeCap = Paint.Cap.ROUND }
            if (f >= 1f) { p.strokeCap = Paint.Cap.BUTT; cv.drawArc(r, 0f, 360f, false, p) } else cv.drawArc(r, -90f, 360f * f, false, p)
        }
    }
    return bmp
}

private fun grouped(n: Number) = "%,d".format(Locale.forLanguageTag("de-CH"), n.toLong())

class RingsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetState.load(context)
        val (day, goals) = state?.takeIf { it.first.date == LocalDate.now() } ?: (DaySummary(LocalDate.now()) to (state?.second ?: Goals()))
        val fr = listOf(day.steps / goals.steps.toFloat(), (day.activeKcal / goals.moveKcal).toFloat(), day.trainingMin / goals.exerciseMin.toFloat())
        val bmp = ringsBitmap(240, fr)
        provideContent {
            Row(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(BG).padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(ImageProvider(bmp), "Ringe", GlanceModifier.size(64.dp))
                Spacer(GlanceModifier.width(14.dp))
                Column {
                    Line("Schritte", grouped(day.steps), androidx.compose.ui.graphics.Color(MOVE))
                    Line("Bewegen", grouped(day.activeKcal.toInt()) + " kcal", androidx.compose.ui.graphics.Color(KCAL))
                    Line("Trainieren", "${day.trainingMin} min", androidx.compose.ui.graphics.Color(HEART))
                }
            }
        }
    }

    @Composable
    private fun Line(label: String, value: String, c: Color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = TextStyle(color = ColorProvider(INK), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(6.dp))
            Text(label, style = TextStyle(color = ColorProvider(c), fontSize = 12.sp))
        }
    }
}

class RingsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingsWidget()
}
