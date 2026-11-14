package ch.widmer.airview.ui

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.widmer.airview.data.RingDay
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.roundToInt

data class Goals(val steps: Int = 10_000, val moveKcal: Int = 500, val exerciseMin: Int = 30)

object GoalStore {
    private fun prefs(c: Context) = c.getSharedPreferences("goals", Context.MODE_PRIVATE)
    fun load(c: Context) = prefs(c).let {
        Goals(it.getInt("steps", 10_000), it.getInt("move", 500), it.getInt("ex", 30))
    }
    fun save(c: Context, g: Goals) = prefs(c).edit()
        .putInt("steps", g.steps).putInt("move", g.moveKcal).putInt("ex", g.exerciseMin).apply()
}

private data class Ring(val label: String, val value: Double, val goal: Double, val color: Color, val unit: String)

@Composable
private fun ringsFor(d: RingDay, g: Goals): List<Ring> {
    val p = LocalPalette.current
    return listOf(
        Ring("Schritte", d.steps.toDouble(), g.steps.toDouble(), p.move, ""),
        Ring("Bewegen", d.moveKcal, g.moveKcal.toDouble(), p.awake, "kcal"),
        Ring("Trainieren", d.exerciseMin.toDouble(), g.exerciseMin.toDouble(), p.heart, "min"),
    )
}

/**
 * Drei konzentrische Ringe. Über 100 % läuft der Ring eine zweite Runde in
 * dunklerem Ton weiter, damit Übererfüllung sichtbar bleibt.
 */
@Composable
fun RingStack(day: RingDay, goals: Goals, size: Dp, stroke: Dp, gap: Dp = 3.dp, progress: Float = 1f, detailed: Boolean = true) {
    val p = LocalPalette.current
    val rings = ringsFor(day, goals)
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        rings.forEachIndexed { i, r ->
            val inset = sw / 2 + i * (sw + gap.toPx())
            val frac = (r.value / r.goal).toFloat() * FastOutSlowInEasing.transform(stagger(progress, i, 3, .25f))
            ring(inset, sw, frac, r.color, p.ink, p.bg, detailed)
        }
    }
}

private fun DrawScope.ring(inset: Float, sw: Float, frac: Float, c: Color, ink: Color, bg: Color, detailed: Boolean) {
    val tl = Offset(inset, inset)
    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
    val r = sz.width / 2
    val center = Offset(size.width / 2, size.height / 2)
    fun at(turns: Float): Offset {
        val a = Math.toRadians(-90.0 + 360.0 * turns)
        return Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y + r * kotlin.math.sin(a).toFloat())
    }
    // Spur
    drawCircle(c.copy(alpha = .16f), r, center, style = Stroke(sw))
    if (frac <= 0.002f) return
    if (frac < 1f) {
        drawArc(c, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        return
    }
    // Ziel erreicht: erste Runde als geschlossener Kreis (keine Kappen, die sich überlagern)
    drawCircle(c, r, center, style = Stroke(sw))
    val extra = frac - 1f
    if (!detailed || extra < 0.004f) return
    // zweite Runde: dunkler, flacher Anfang, runde Spitze
    val lap = extra.coerceAtMost(.995f)
    val dark = lerp(c, ink, .22f)
    drawArc(dark, -90f, 360f * lap, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Butt))
    val tip = at(lap)
    drawCircle(dark, sw / 2, tip)
}

/** Ringe-Abschnitt für «Heute»: grosse Ringe, Werte daneben, darunter die Woche zum Antippen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RingsSection(days: List<RingDay>, goals: Goals, onGoals: (Goals) -> Unit) {
    if (days.isEmpty()) return
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var sel by remember(days) { mutableIntStateOf(days.lastIndex) }
    val day = days[sel]
    val r = reveal()
    // beim Tageswechsel neu aufziehen
    val swap = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(sel) { if (first) first = false else { swap.snapTo(0f); swap.animateTo(1f, tween(700)) } }
    val rings = ringsFor(day, goals)
    val allClosed = rings.all { it.value >= it.goal }

    Section("Ringe", if (day.date == LocalDate.now()) "heute" else day.date.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.GERMAN))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editing = true }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { val s = .92f + .08f * r; scaleX = s; scaleY = s }) {
            RingStack(day, goals, 156.dp, 17.dp, 5.dp, progress = minOf(r, swap.value))
        }
        Spacer(Modifier.width(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rings.forEach { ring ->
                Column {
                    Text(ring.label, style = Type.small, color = ring.color)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%,d".format(Locale.forLanguageTag("de-CH"), (ring.value * r).roundToInt()), style = Type.numeral.copy(fontSize = 28.sp, lineHeight = 28.sp), color = p.ink)
                        Text(" / ${"%,d".format(Locale.forLanguageTag("de-CH"), ring.goal.roundToInt())} ${ring.unit}".trimEnd(), style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        when {
            allClosed -> "Alle drei Ringe geschlossen."
            else -> "Lange drücken, um die Ziele zu ändern."
        },
        style = Type.small, color = if (allClosed) p.ink else p.muted,
    )
    Spacer(Modifier.height(18.dp))
    // Woche
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, d ->
            val chosen = i == sel
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) p.track.copy(alpha = .7f) else Color.Transparent)
                    .clickable { sel = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    .padding(horizontal = 5.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RingStack(d, goals, 34.dp, 4.dp, 1.5.dp, progress = stagger(r, i, days.size, .5f), detailed = false)
                Spacer(Modifier.height(4.dp))
                Text(
                    d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.GERMAN).take(2),
                    style = Type.small, color = if (chosen) p.ink else p.muted, textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (editing) GoalsDialog(goals, onDismiss = { editing = false }) { g ->
        onGoals(g); GoalStore.save(ctx, g); editing = false
    }
}

@Composable
private fun GoalsDialog(start: Goals, onDismiss: () -> Unit, onSave: (Goals) -> Unit) {
    val p = LocalPalette.current
    var g by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.bar, titleContentColor = p.ink, textContentColor = p.ink,
        title = { Text("Ziele", style = Type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Stepper("Schritte", "%,d".format(Locale.forLanguageTag("de-CH"), g.steps), p.move,
                    { g = g.copy(steps = (g.steps - 500).coerceAtLeast(1000)) },
                    { g = g.copy(steps = (g.steps + 500).coerceAtMost(40_000)) })
                Stepper("Bewegen", "${g.moveKcal} kcal", p.awake,
                    { g = g.copy(moveKcal = (g.moveKcal - 50).coerceAtLeast(100)) },
                    { g = g.copy(moveKcal = (g.moveKcal + 50).coerceAtMost(2000)) })
                Stepper("Trainieren", "${g.exerciseMin} min", p.heart,
                    { g = g.copy(exerciseMin = (g.exerciseMin - 5).coerceAtLeast(5)) },
                    { g = g.copy(exerciseMin = (g.exerciseMin + 5).coerceAtMost(180)) })
                Text("Als Trainingsminute zählt jede Minute mit mindestens 100 Schritten oder in einem aufgezeichneten Training.", style = Type.small, color = p.muted)
            }
        },
        confirmButton = { TextButton({ onSave(g) }) { Text("Ziele speichern", style = Type.title.copy(fontSize = 15.sp), color = p.ink) } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen", style = Type.body, color = p.muted) } },
    )
}

@Composable
private fun Stepper(label: String, value: String, c: Color, minus: () -> Unit, plus: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.small, color = c)
            Text(value, style = Type.numeral.copy(fontSize = 26.sp), color = p.ink)
        }
        StepBtn("−", minus); Spacer(Modifier.width(8.dp)); StepBtn("+", plus)
    }
}

@Composable
private fun StepBtn(t: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(p.track).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(t, style = Type.title, color = p.ink) }
}
