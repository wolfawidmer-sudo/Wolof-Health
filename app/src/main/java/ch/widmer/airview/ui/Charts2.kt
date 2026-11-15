package ch.widmer.airview.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.widmer.airview.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** Pulszonen als Rot-Abstufung: Zone 1 hell, Zone 5 voll. */
@Composable
fun zoneColors(): List<Color> {
    val p = LocalPalette.current
    return listOf(.26f, .44f, .62f, .8f, 1f).map { lerp(p.bg, p.heart, it) }
}

val ZONE_NAMES = listOf("Locker", "Leicht", "Mittel", "Hart", "Maximal")

/**
 * Belastung pro Tag als nach Zonen gestapelte Balken (TRIMP-Anteile), optional mit Zielband.
 * Antippen wählt einen Tag.
 */
@Composable
fun LoadBars(days: List<DaySummary>, band: ClosedFloatingPointRange<Double>?, modifier: Modifier) {
    val p = LocalPalette.current
    val zc = zoneColors()
    val raw = LocalReveal.current
    var sel by remember(days) { mutableIntStateOf(days.lastIndex) }
    val max = maxOf(days.maxOfOrNull { it.trimp } ?: 0.0, band?.endInclusive ?: 0.0, 30.0) * 1.08
    val d = days.getOrNull(sel)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text("%.0f".format((d?.trimp ?: 0.0) * reveal()), style = Type.numeral.copy(fontSize = 30.sp), color = p.ink)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (d?.date == LocalDate.now()) "heute" else d?.date?.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN)) ?: "") +
                    (d?.let { ", ${it.zoneTotal} min in Zonen" } ?: ""),
                style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(days) {
                detectTapGestures { o -> sel = (o.x / (size.width / days.size)).toInt().coerceIn(0, days.lastIndex) }
            },
        ) {
            val n = days.size
            val slot = size.width / n
            val bw = slot * .42f
            band?.let {
                val y1 = (size.height - it.endInclusive / max * size.height).toFloat()
                val y0 = (size.height - it.start / max * size.height).toFloat()
                drawRoundRect(p.heart.copy(alpha = .10f), Offset(0f, y1), Size(size.width, y0 - y1), CornerRadius(8f))
            }
            days.forEachIndexed { i, day ->
                val grow = FastOutSlowInEasing.transform(stagger(raw, i, n, .55f))
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(p.track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                var y = size.height
                val alpha = if (i == sel) 1f else .75f
                // gestapelt von Zone 1 unten bis Zone 5 oben
                val shape = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, 0f, x + bw, size.height, CornerRadius(bw / 2))) }
                clipPath(shape) {
                    day.zoneMin.forEachIndexed { z, m ->
                        val h = (m * (z + 1) / max * size.height).toFloat() * grow
                        if (h > 0) { drawRect(zc[z].copy(alpha = alpha), Offset(x, y - h + 1.5f), Size(bw, (h - 1.5f).coerceAtLeast(1f))); y -= h }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, it ->
                val label = if (days.size > 7) (if ((days.size - 1 - i) % 2 == 0) it.date.dayOfMonth.toString() else "")
                    else it.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.GERMAN).take(2)
                Text(label, Modifier.weight(1f),
                    style = Type.small, color = if (i == sel) p.ink else p.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** 24 Stundenbalken, die aktuelle Stunde voll gefärbt. */
@Composable
fun HourBars(values: List<Double>, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    val now = java.time.LocalTime.now().hour
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val r = reveal()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val slot = size.width / 24; val bw = slot * .6f
            values.forEachIndexed { h, v ->
                val x = h * slot + (slot - bw) / 2
                val hh = (v / max * size.height).toFloat() * r
                drawRoundRect(p.track, Offset(x, size.height - 3f), Size(bw, 3f), CornerRadius(2f))
                if (hh > 0) drawRoundRect(if (h == now) color else color.copy(alpha = .6f), Offset(x, size.height - hh), Size(bw, hh), CornerRadius(bw / 2))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = Type.small, color = p.muted) }
        }
    }
}

/**
 * Verlauf mit persönlichem Normalbereich als Band. Werte ausserhalb sind hohle Punkte.
 */
@Composable
fun RangeChart(values: List<DayValue>, range: NormalRange?, color: Color, modifier: Modifier, dots: Boolean = true) {
    val p = LocalPalette.current
    if (values.isEmpty()) return
    val r = reveal()
    val all = values.map { it.value } + listOfNotNull(range?.low, range?.high)
    val lo = all.min(); val hi = all.max().let { if (it == lo) lo + 1 else it }
    val pad = (hi - lo) * .12
    val first = values.first().date; val last = values.last().date
    val spanDays = (last.toEpochDay() - first.toEpochDay()).coerceAtLeast(1)
    Canvas(modifier) {
        fun x(d: LocalDate) = ((d.toEpochDay() - first.toEpochDay()).toFloat() / spanDays) * (size.width - 12f) + 6f
        fun y(v: Double) = (size.height - ((v - lo + pad) / (hi - lo + 2 * pad)) * size.height).toFloat()
        range?.let {
            drawRect(color.copy(alpha = .13f), Offset(0f, y(it.high)), Size(size.width, y(it.low) - y(it.high)))
            drawLine(color.copy(alpha = .35f), Offset(0f, y(it.mean)), Offset(size.width, y(it.mean)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f)))
        }
        if (values.size >= 2) {
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(v.date), y(v.value)) else path.lineTo(x(v.date), y(v.value)) }
            clipRect(right = size.width * r + 8f) {
                drawPath(path, color, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (dots && r > .95f) values.forEach { v ->
            val inside = range?.contains(v.value) ?: true
            val c = Offset(x(v.date), y(v.value))
            if (inside) drawCircle(color, 4.5f, c)
            else { drawCircle(p.bg, 6.5f, c); drawCircle(color, 6.5f, c, style = Stroke(2.5f)) }
        }
    }
}

/**
 * Schlafrhythmus: pro Nacht ein schwebender Balken von Zubettgehen bis Aufwachen
 * auf einer Achse von 20 bis 12 Uhr. Dauer und Regelmässigkeit in einem Bild.
 */
@Composable
fun SleepWindows(nights: List<SleepNight>, modifier: Modifier, slots: Int = 14) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val raw = LocalReveal.current
    val end = LocalDate.now()
    val dates = (slots - 1 downTo 0).map { end.minusDays(it.toLong()) }
    val byDate = nights.associateBy { it.date }
    // Achse: Minuten ab 20:00, 16 Stunden
    fun axis(i: java.time.Instant): Float { val t = i.atZone(zone).toLocalTime(); return (((t.hour * 60 + t.minute) - 20 * 60 + 1440) % 1440) / 960f }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxHeight().width(34.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("20", "24", "4", "8", "12").forEach { Text(it, style = Type.small, color = p.muted) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                for (k in 0..4) { val y = size.height * k / 4f; drawLine(p.track, Offset(0f, y), Offset(size.width, y), 1f) }
                val slot = size.width / slots; val bw = slot * .5f
                dates.forEachIndexed { i, d ->
                    val x = i * slot + (slot - bw) / 2
                    val night = byDate[d]
                    if (night == null) { drawCircle(p.track, 2.5f, Offset(x + bw / 2, size.height / 2)); return@forEachIndexed }
                    val g = FastOutSlowInEasing.transform(stagger(raw, i, slots, .55f))
                    val y0 = axis(night.start) * size.height
                    val y1 = (axis(night.end).let { if (it < axis(night.start)) 1f else it }) * size.height
                    drawRoundRect(if (d == end) p.sleep else p.sleepLight, Offset(x, y0), Size(bw, ((y1 - y0) * g).coerceAtLeast(2f)), CornerRadius(bw / 2))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
            dates.forEachIndexed { i, d ->
                Text(if ((slots - 1 - i) % 2 == 0) d.dayOfMonth.toString() else "", Modifier.weight(1f),
                    style = Type.small, color = if (d == end) p.ink else p.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Bogen (270°) für die Erholung: Zahl in der Mitte, Stufenwort darunter. */
@Composable
fun Gauge(score: Int, label: String, size: androidx.compose.ui.unit.Dp, color: Color) {
    val p = LocalPalette.current
    val r = reveal()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.width * .085f
            val inset = sw / 2
            val tl = Offset(inset, inset); val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(p.track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            // Grenzen der Stufen (34, 67) als feine Marken
            listOf(34, 67).forEach { m ->
                val a = Math.toRadians(135.0 + 270.0 * m / 100)
                val rad = sz.width / 2; val c = Offset(this.size.width / 2, this.size.height / 2)
                val o1 = Offset(c.x + (rad - sw * .7f) * Math.cos(a).toFloat(), c.y + (rad - sw * .7f) * Math.sin(a).toFloat())
                val o2 = Offset(c.x + (rad + sw * .7f) * Math.cos(a).toFloat(), c.y + (rad + sw * .7f) * Math.sin(a).toFloat())
                drawLine(p.bg, o1, o2, 3f)
            }
            drawArc(color, 135f, 270f * score / 100f * r, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(score * r).toInt()}", style = Type.numeral.copy(fontSize = (size.value * .34f).sp, lineHeight = (size.value * .34f).sp), color = p.ink)
            Text(label, style = Type.title.copy(fontSize = (size.value * .075f).coerceAtLeast(15f).sp), color = color)
        }
    }
}

/** Horizontaler Balken für einen Anteil, z. B. Punkte eines Schlafwerts. */
@Composable
fun PartBar(label: String, value: Int, max: Int, color: Color, note: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
            Text("$value von $max", style = Type.small, color = p.muted)
        }
        Spacer(Modifier.height(6.dp))
        Meter(value / max.toFloat(), color)
        if (note != null) Text(note, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Wirkung eines Bestandteils (−/+) um eine Mittellinie. */
@Composable
fun EffectBar(effect: Double, color: Color) {
    val p = LocalPalette.current
    Canvas(Modifier.width(90.dp).height(10.dp)) {
        val mid = size.width / 2
        drawRoundRect(p.track, cornerRadius = CornerRadius(5f))
        val w = ((effect / .6).coerceIn(-1.0, 1.0) * mid).toFloat()
        if (w >= 0) drawRoundRect(color, Offset(mid, 0f), Size(w.coerceAtLeast(2f), size.height), CornerRadius(5f))
        else drawRoundRect(p.awake, Offset(mid + w, 0f), Size(-w, size.height), CornerRadius(5f))
        drawLine(p.ink.copy(alpha = .5f), Offset(mid, -2f), Offset(mid, size.height + 2f), 2f)
    }
}
