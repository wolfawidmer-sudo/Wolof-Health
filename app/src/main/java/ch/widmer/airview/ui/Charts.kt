package ch.widmer.airview.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import ch.widmer.airview.data.DayValue
import ch.widmer.airview.data.HrPoint
import ch.widmer.airview.data.SleepNight
import ch.widmer.airview.data.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private val zone get() = ZoneId.systemDefault()

/** Ein Wert, der beim Einblenden der Seite von 0 hochzählt. */
@Composable
fun reveal(): Float = FastOutSlowInEasing.transform(LocalReveal.current)

/** Gestaffelt: Element i beginnt etwas später als Element i-1. */
fun stagger(r: Float, i: Int, n: Int, spread: Float = .45f): Float {
    val start = if (n <= 1) 0f else spread * i / (n - 1)
    return ((r - start) / (1f - spread)).coerceIn(0f, 1f)
}

/**
 * Der Tag als ein Instrument: 00–24 Uhr, oben der Puls, unten ein Band mit
 * Schlaf und Trainings. Mit dem Finger über die Kurve streichen zeigt den Puls zu jeder Uhrzeit.
 */
@Composable
fun DayStrip(day: LocalDate, hr: List<HrPoint>, sleep: List<SleepNight>, workouts: List<Workout>, modifier: Modifier = Modifier.height(200.dp)) {
    val p = LocalPalette.current
    val r = reveal()
    val haptic = LocalHapticFeedback.current
    val start = day.atStartOfDay(zone).toInstant().epochSecond
    val span = 86_400f
    fun fx(i: Instant, w: Float) = ((i.epochSecond - start) / span).coerceIn(0f, 1f) * w
    val binned = remember(hr) {
        hr.groupBy { (it.time.epochSecond - start) / 300 }
            .map { (k, v) -> k * 300f to v.map { it.bpm }.average().toFloat() }.sortedBy { it.first }
    }
    val lo = (binned.minOfOrNull { it.second } ?: 50f) - 4
    val hi = (binned.maxOfOrNull { it.second } ?: 120f) + 4
    val nowX = ((Instant.now().epochSecond - start) / span).coerceIn(0f, 1f)

    var scrub by remember { mutableStateOf<Float?>(null) }   // 0..1 über den Tag
    val scrubPoint = scrub?.let { s -> binned.minByOrNull { kotlin.math.abs(it.first / span - s) } }
    val lastSnap = remember { mutableIntStateOf(-1) }

    val pulse = rememberInfiniteTransition(label = "now")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing)), label = "ring")

    Column(modifier) {
        // Ablesezeile
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (scrubPoint != null) {
                Text("um ${hhmm(Instant.ofEpochSecond(start + scrubPoint.first.toLong()))}", style = Type.small, color = p.muted)
                Spacer(Modifier.width(8.dp))
                Text("${scrubPoint.second.toInt()} bpm", style = Type.title.copy(fontSize = 16.sp()), color = p.heart)
            } else {
                Text(
                    if (binned.isEmpty()) "Noch kein Puls heute" else "jetzt ${binned.last().second.toInt()} bpm",
                    style = Type.small, color = p.muted,
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(binned) {
                if (binned.isEmpty()) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    scrub = (down.position.x / size.width).coerceIn(0f, 1f)
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.first()
                        scrub = (c.position.x / size.width).coerceIn(0f, 1f)
                        val hour = (scrub!! * 24).toInt()
                        if (hour != lastSnap.intValue) { lastSnap.intValue = hour; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (c.positionChange() != Offset.Zero) c.consume()
                    } while (ev.changes.any { it.pressed })
                    scrub = null
                }
            },
        ) {
            val hrH = size.height - 30.dp.toPx()
            val bandY = size.height - 16.dp.toPx()
            val bandH = 16.dp.toPx()
            for (h in 0..24 step 3) {
                val x = size.width * h / 24f
                drawLine(p.track, Offset(x, 0f), Offset(x, hrH), if (h % 6 == 0) 1.5f else 0.8f)
            }
            drawRoundRect(p.track, Offset(0f, bandY), Size(size.width, bandH), CornerRadius(bandH / 2))
            // Band wächst von links mit dem Reveal
            clipRect(right = size.width * r) {
                sleep.filter { it.end.epochSecond > start && it.start.epochSecond < start + 86_400 }.forEach { n ->
                    val x0 = fx(n.start, size.width); val x1 = fx(n.end, size.width)
                    drawRoundRect(p.sleep, Offset(x0, bandY), Size((x1 - x0).coerceAtLeast(2f), bandH), CornerRadius(bandH / 2))
                }
                workouts.filter { it.end.epochSecond > start && it.start.epochSecond < start + 86_400 }.forEach { w ->
                    val x0 = fx(w.start, size.width); val x1 = fx(w.end, size.width)
                    drawRoundRect(p.move, Offset(x0, bandY), Size((x1 - x0).coerceAtLeast(4f), bandH), CornerRadius(bandH / 2))
                }
                val path = Path()
                var prevX = -1f
                binned.forEach { (t, bpm) ->
                    val x = size.width * t / span
                    val y = hrH - (bpm - lo) / (hi - lo) * (hrH - 8f)
                    if (prevX < 0 || x - prevX > size.width * 1800 / span) path.moveTo(x, y) else path.lineTo(x, y)
                    prevX = x
                }
                drawPath(path, p.heart, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // jetzt – mit ruhigem Puls-Ring
            if (day == LocalDate.now(zone) && r > .95f) {
                val x = nowX * size.width
                drawLine(p.ink.copy(alpha = .7f), Offset(x, 0f), Offset(x, size.height), 1.5f)
                drawCircle(p.ink, 4.5f, Offset(x, 0f))
                drawCircle(p.ink.copy(alpha = (1f - ring) * .5f), 4.5f + ring * 14f, Offset(x, 0f), style = Stroke(1.5f))
            }
            // Ablese-Cursor
            scrubPoint?.let { (t, bpm) ->
                val x = size.width * t / span
                val y = hrH - (bpm - lo) / (hi - lo) * (hrH - 8f)
                drawLine(p.heart.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), 1.5f)
                drawCircle(p.bg, 9f, Offset(x, y))
                drawCircle(p.heart, 6.5f, Offset(x, y))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = Type.small, color = p.muted) }
        }
    }
}

/**
 * Balken pro Tag, wachsen gestaffelt. Antippen wählt einen Tag und zeigt seinen Wert.
 */
@Composable
fun DayBars(
    values: List<DayValue>, color: Color, goal: Double? = null, modifier: Modifier = Modifier.height(190.dp),
    format: (Double) -> String = { "%,.0f".format(Locale.forLanguageTag("de-CH"), it) },
) {
    val p = LocalPalette.current
    if (values.isEmpty()) return
    val r = reveal()
    val haptic = LocalHapticFeedback.current
    var selected by remember(values) { mutableIntStateOf(values.lastIndex) }
    val max = maxOf(values.maxOf { it.value }, goal ?: 0.0, 1.0) * 1.05
    val sel = values[selected]
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                format(sel.value * r), style = Type.numeral.copy(fontSize = 30.sp()), color = p.ink,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (sel.date == LocalDate.now()) "heute" else sel.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)),
                style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(values) {
                detectTapGestures { o ->
                    val i = (o.x / (size.width / values.size)).toInt().coerceIn(0, values.lastIndex)
                    if (i != selected) { selected = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            },
        ) {
            val n = values.size
            val slot = size.width / n
            val bw = slot * if (n > 10) 0.55f else 0.38f
            values.forEachIndexed { i, v ->
                val grow = FastOutSlowInEasing.transform(stagger(r, i, n))
                val h = (v.value / max * size.height).toFloat() * grow
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(p.track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                if (h > 0) drawRoundRect(
                    if (i == selected) color else color.copy(alpha = 0.45f),
                    Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2),
                )
            }
            goal?.let {
                val y = (size.height - it / max * size.height).toFloat()
                drawLine(p.ink.copy(alpha = .6f * r), Offset(0f, y), Offset(size.width * r, y), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, it ->
                val label = if (values.size > 7) (if ((values.size - 1 - i) % 2 == 0) it.date.dayOfMonth.toString() else "")
                else it.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.GERMAN).take(2)
                Text(label, Modifier.weight(1f), style = Type.small,
                    color = if (i == selected) p.ink else p.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Kleine Verlaufslinie, die sich beim Einblenden zeichnet. */
@Composable
fun Spark(values: List<DayValue>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val r = reveal()
    val lo = values.minOf { it.value }
    val hi = values.maxOf { it.value }.let { if (it == lo) lo + 1 else it }
    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v.value)) else path.lineTo(i * step, y(v.value)) }
        clipRect(right = size.width * r + 6f) {
            drawPath(path, color, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (r > .98f) drawCircle(color, 5f, Offset(size.width, y(values.last().value)))
    }
}

/** Fortschrittsbalken, füllt sich beim Einblenden. */
@Composable
fun Meter(fraction: Float, color: Color, delay: Int = 0, of: Int = 1) {
    val p = LocalPalette.current
    val r = FastOutSlowInEasing.transform(stagger(LocalReveal.current, delay, of, .3f))
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val cr = CornerRadius(size.height / 2)
        drawRoundRect(p.track, cornerRadius = cr)
        val w = size.width * fraction.coerceIn(0f, 1f) * r
        if (w > 0) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = cr)
    }
}

fun stageRow(stage: Int): Int = when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 0
    SleepSessionRecord.STAGE_TYPE_REM -> 1
    SleepSessionRecord.STAGE_TYPE_DEEP -> 3
    else -> 2
}

/** Hypnogramm, das sich von links nach rechts durch die Nacht aufbaut. Antippen zeigt die Phase. */
@Composable
fun Hypnogram(night: SleepNight, modifier: Modifier = Modifier.height(190.dp)) {
    val p = LocalPalette.current
    val r = reveal()
    val total = (night.end.epochSecond - night.start.epochSecond).toFloat().coerceAtLeast(1f)
    val labels = listOf("Wach", "REM", "Leicht", "Tief")
    val stages = remember(night) { night.stages.sortedBy { it.start } }
    var picked by remember(night) { mutableStateOf<ch.widmer.airview.data.SleepStage?>(null) }
    Column(modifier) {
        if (picked != null) Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            val st = picked
            if (st != null) {
                Text(labels[stageRow(st.stage)], style = Type.title.copy(fontSize = 16.sp()), color = p.ink)
                Spacer(Modifier.width(8.dp))
                Text("${hhmm(st.start)} bis ${hhmm(st.end)}, ${java.time.Duration.between(st.start, st.end).toMinutes()} min",
                    style = Type.small, color = p.muted)
            } else if (stages.isNotEmpty()) {
                Unit
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxHeight().width(54.dp), verticalArrangement = Arrangement.SpaceAround) {
                labels.forEach { Text(it, style = Type.small, color = p.muted) }
            }
            Canvas(
                Modifier.weight(1f).fillMaxHeight().pointerInput(stages) {
                    detectTapGestures { o ->
                        val t = night.start.epochSecond + (o.x / size.width * total).toLong()
                        picked = stages.firstOrNull { t in it.start.epochSecond until it.end.epochSecond }
                    }
                },
            ) {
                val rowH = size.height / 4
                for (row in 0..3) drawRoundRect(p.track, Offset(0f, rowH * row + rowH * .38f), Size(size.width, rowH * .24f), CornerRadius(4f))
                if (stages.isEmpty()) {
                    drawRoundRect(p.sleep, Offset(0f, rowH * 2.2f), Size(size.width * r, rowH * .6f), CornerRadius(6f))
                    return@Canvas
                }
                clipRect(right = size.width * r) {
                    stages.forEach { st ->
                        val row = stageRow(st.stage)
                        val x0 = (st.start.epochSecond - night.start.epochSecond) / total * size.width
                        val x1 = (st.end.epochSecond - night.start.epochSecond) / total * size.width
                        val c = when (row) { 0 -> p.awake; 3 -> p.sleep; else -> p.sleepLight }
                        val isPicked = picked == st
                        val grow = if (isPicked) rowH * .08f else 0f
                        drawRoundRect(
                            c.copy(alpha = if (picked == null || isPicked) 1f else .45f),
                            Offset(x0, rowH * row + rowH * .18f - grow),
                            Size((x1 - x0).coerceAtLeast(2f), rowH * .64f + grow * 2), CornerRadius(5f),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 54.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hhmm(night.start), style = Type.small, color = p.muted)
            Text(hhmm(night.end), style = Type.small, color = p.muted)
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

fun hhmm(i: Instant): String = i.atZone(zone).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }
fun dur(min: Long): String = "${min / 60}:${"%02d".format(min % 60)}"
