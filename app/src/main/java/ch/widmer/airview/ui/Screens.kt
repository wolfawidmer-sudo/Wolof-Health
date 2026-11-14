package ch.widmer.airview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.SleepSessionRecord as S
import ch.widmer.airview.data.DayValue
import ch.widmer.airview.data.SleepNight
import ch.widmer.airview.data.Snapshot
import ch.widmer.airview.data.SourceStore
import androidx.compose.foundation.clickable
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val DE = Locale.GERMAN
private fun Number.grouped(): String = "%,d".format(Locale.forLanguageTag("de-CH"), toLong())

// ---------- Bausteine ----------

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun Section(title: String, aside: String? = null) {
    val p = LocalPalette.current
    Spacer(Modifier.height(36.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = Type.small, color = p.muted)
    }
}

@Composable
fun Big(value: String, unit: String? = null, size: Int = 64, color: Color = LocalPalette.current.ink) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = Type.numeral.copy(fontSize = size.sp, lineHeight = (size * 0.98).sp), color = color)
        if (unit != null) Text(unit, style = Type.body.copy(fontSize = (size / 3.6).coerceAtLeast(13.0).sp), color = p.muted,
            modifier = Modifier.padding(start = 5.dp, bottom = (size / 8).dp))
    }
}

@Composable
fun Stat(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Big(value, unit, size = 34)
        Text(label, style = Type.small, color = p.muted)
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = Type.body, color = LocalPalette.current.muted, modifier = Modifier.padding(vertical = 4.dp))
}

/** Neu laden, z. B. nach dem Ändern der Datenquellen. */
val LocalRefresh = compositionLocalOf<() -> Unit> { {} }

/** Zeigt, aus welchen Apps die Daten stammen; antippen öffnet die Auswahl. */
@Composable
fun SourceLine(s: Snapshot) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val refresh = LocalRefresh.current
    var open by remember { mutableStateOf(false) }
    val active = s.sources.filter { s.activeSources.isEmpty() || it.pkg in s.activeSources }
    val label = when {
        s.sources.isEmpty() -> "Keine Datenquellen"
        s.activeSources.isEmpty() -> "Daten aus allen Apps"
        active.isEmpty() -> "Keine Quelle gewählt"
        else -> active.joinToString(" und ") { it.label }
    }
    Text(
        label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = Type.small.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = p.muted, modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
    )
    if (open) {
        var pick by remember { mutableStateOf(if (s.activeSources.isEmpty()) s.sources.map { it.pkg }.toSet() else s.activeSources) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            containerColor = p.bar, titleContentColor = p.ink, textContentColor = p.ink,
            title = { Text("Datenquellen", style = Type.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Gezählt wird nur, was angehakt ist.",
                        style = Type.small, color = p.muted)
                    s.sources.forEach { src ->
                        val on = src.pkg in pick
                        Row(Modifier.fillMaxWidth().clickable { pick = if (on) pick - src.pkg else pick + src.pkg }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(on, { pick = if (on) pick - src.pkg else pick + src.pkg },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = p.move, uncheckedColor = p.muted, checkmarkColor = p.bg))
                            Column {
                                Text(src.label + if (SourceStore.isBand(src)) " (dein Band)" else "", style = Type.body, color = p.ink)
                                Text("seit ${src.firstDay.format(DateTimeFormatter.ofPattern("d. MMMM", DE))}, an ${src.days} ${if (src.days == 1) "Tag" else "Tagen"}",
                                    style = Type.small, color = p.muted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton({ SourceStore.save(ctx, pick); open = false; refresh() }, enabled = pick.isNotEmpty()) {
                    Text("Übernehmen", style = Type.title.copy(fontSize = 15.sp), color = p.ink)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton({ open = false }) { Text("Abbrechen", style = Type.body, color = p.muted) } },
        )
    }
}

// ---------- Heute ----------

@Composable
fun TodayScreen(s: Snapshot) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val r = reveal()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var goals by remember { mutableStateOf(GoalStore.load(ctx)) }
    val STEP_GOAL = goals.steps.toDouble()
    Page {
        Text(today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)), style = Type.title, color = p.muted)
        SourceLine(s)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text((s.steps * r).grouped(), style = Type.numeral.copy(fontSize = 104.sp, lineHeight = 100.sp), color = p.ink)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 14.dp)) {
                Text("Schritte", style = Type.title, color = p.ink)
                Text(
                    if (s.steps >= STEP_GOAL) "Ziel erreicht" else "noch ${(STEP_GOAL - s.steps).grouped()}",
                    style = Type.small, color = p.muted,
                )
            }
        }

        RingsSection(s.rings, goals) { goals = it }

        Section("Dein Tag")
        DayStrip(today, s.hrToday, s.sleep, s.workouts)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(p.heart, "Puls"); Legend(p.sleep, "Schlaf"); Legend(p.move, "Training")
        }

        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Distanz", "%.1f".format(s.distanceM / 1000 * r), "km", Modifier.weight(1f))
            Stat("aktive Kalorien", (s.activeKcal * r).grouped(), "kcal", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Trainingsminuten", (s.activeMinutes * r).toInt().toString(), "min", Modifier.weight(1f))
            Stat("Stockwerke", (s.floors * r).roundToInt().toString(), null, Modifier.weight(1f))
        }

        val night = s.sleep.lastOrNull()?.takeIf { it.date == today }
        if (night != null || s.restingHr.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                night?.let { Stat("Schlaf letzte Nacht", dur((it.asleepMinutes * r).toLong()), "h", Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
                s.restingHr.lastOrNull()?.let { Stat("Ruhepuls", (it.value * r).roundToInt().toString(), "bpm", Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(c) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.small, color = LocalPalette.current.muted)
    }
}

// ---------- Fitness ----------

@Composable
fun FitnessScreen(s: Snapshot) {
    val p = LocalPalette.current
    val STEP_GOAL = GoalStore.load(androidx.compose.ui.platform.LocalContext.current).steps.toDouble()
    Page {
        val avg = s.stepsWeek.map { it.value }.average()
        Section("Schritte, 7 Tage", if (!avg.isNaN()) "Schnitt ${avg.grouped()}" else null)
        if (s.stepsWeek.isNotEmpty()) DayBars(s.stepsWeek, p.move, STEP_GOAL) else Note("Noch keine Schritte.")

        if (s.kcalWeek.any { it.value > 0 }) {
            Section("Kalorien", "kcal gesamt")
            DayBars(s.kcalWeek, p.move, height = 90)
        }

        s.vo2max?.let {
            Section("Cardio-Fitness")
            Big("%.0f".format(it * reveal()), "VO₂max, ml/kg/min", 56)
        }

        Section("Trainings", "30 Tage")
        if (s.workouts.isEmpty()) Note("Keine Trainings in den letzten 30 Tagen. Das Fitbit Air erkennt Gehen, Laufen und Velofahren automatisch; andere Trainings startest du in Google Health.")
        s.workouts.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Canvas(Modifier.padding(top = 7.dp).size(10.dp)) { drawCircle(p.move) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = Type.title.copy(fontSize = 17.sp), color = p.ink)
                    Text(
                        w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d. MMMM, HH:mm", DE)),
                        style = Type.small, color = p.muted,
                    )
                    val extra = listOfNotNull(
                        w.distanceM?.takeIf { it > 50 }?.let { "%.2f km".format(it / 1000) },
                        w.calories?.takeIf { it > 0 }?.let { "${it.roundToInt()} kcal" },
                        w.avgHr?.let { "Puls im Schnitt $it" },
                    ).joinToString(", ")
                    if (extra.isNotEmpty()) Text(extra, style = Type.small, color = p.muted)
                }
                Big(dur(Duration.between(w.start, w.end).toMinutes()), "h", 28)
            }
        }
    }
}

// ---------- Schlaf ----------

@Composable
fun SleepScreen(s: Snapshot) {
    val p = LocalPalette.current
    val n = s.sleep.lastOrNull()
    Page {
        if (n == null) {
            Section("Schlaf")
            Note("Noch keine Nacht erfasst.")
            return@Page
        }
        Text("Nacht auf ${n.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE))}", style = Type.title, color = p.muted)
        Spacer(Modifier.height(8.dp))
        Big(dur((n.asleepMinutes * reveal()).toLong()), "h geschlafen", 96, color = p.ink)
        val inBed = Duration.between(n.start, n.end).toMinutes()
        Text("${n.asleepMinutes * 100 / inBed.coerceAtLeast(1)} % von ${dur(inBed)} h im Bett",
            style = Type.body, color = p.muted)

        Section("Schlafphasen")
        Hypnogram(n)
        if (n.stages.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            StageRows(n)
        } else Note("Keine Schlafphasen erfasst.")

        if (s.sleep.size > 1) {
            Section("Schlafdauer", "Schnitt ${dur(s.sleep.map { it.asleepMinutes }.average().toLong())} h")
            DayBars(s.sleep.takeLast(14).map { DayValue(it.date, it.asleepMinutes / 60.0) }, p.sleep, goal = 8.0, height = 110,
                format = { "${dur((it * 60).toLong())} h" })
        }
    }
}

@Composable
private fun StageRows(n: SleepNight) {
    val p = LocalPalette.current
    val rows = listOf(
        Triple("Wach", n.minutesIn(S.STAGE_TYPE_AWAKE, S.STAGE_TYPE_AWAKE_IN_BED, S.STAGE_TYPE_OUT_OF_BED), p.awake),
        Triple("REM", n.minutesIn(S.STAGE_TYPE_REM), p.sleepLight),
        Triple("Leicht", n.minutesIn(S.STAGE_TYPE_LIGHT, S.STAGE_TYPE_SLEEPING), p.sleepLight),
        Triple("Tief", n.minutesIn(S.STAGE_TYPE_DEEP), p.sleep),
    )
    val total = rows.sumOf { it.second }.coerceAtLeast(1)
    rows.forEachIndexed { i, (label, min, c) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body, color = p.ink, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f)) { Meter(min.toFloat() / total, c, i, rows.size) }
            Text("${dur(min)} h", style = Type.body, color = p.ink, modifier = Modifier.width(76.dp).padding(start = 12.dp))
            Text("${min * 100 / total} %", style = Type.small, color = p.muted, modifier = Modifier.width(40.dp))
        }
    }
}

// ---------- Gesundheit ----------

@Composable
fun HealthScreen(s: Snapshot, footer: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    val latest = listOf(s.restingHr, s.hrv, s.spo2, s.respRate).mapNotNull { it.lastOrNull()?.date }.maxOrNull()
    Page {
        Text("Gesundheit", style = Type.title, color = p.muted)
        Text(
            latest?.let { if (it == LocalDate.now()) "Letzte Nacht" else "Nacht auf " + it.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)) }
                ?: "Noch keine Nacht gemessen",
            style = Type.numeral.copy(fontSize = 40.sp, lineHeight = 44.sp), color = p.ink, modifier = Modifier.padding(top = 4.dp),
        )

        Section("Herz")
        TileRow {
            Tile("Ruhepuls", s.restingHr, "bpm", "%.0f", p.heart, Modifier.weight(1f))
            Tile("HRV", s.hrv, "ms", "%.0f", p.heart, Modifier.weight(1f))
        }
        Section("Atmung")
        TileRow {
            Tile("Sauerstoff", s.spo2, "%", "%.1f", p.sleep, Modifier.weight(1f))
            Tile("Atemfrequenz", s.respRate, "/min", "%.1f", p.sleep, Modifier.weight(1f))
        }
        s.weightKg?.let {
            Section("Körper")
            TileRow { Tile("Gewicht", listOf(DayValue(LocalDate.now(), it)), "kg", "%.1f", p.move, Modifier.weight(1f), trend = false) }
        }

        Spacer(Modifier.height(36.dp))
        Text("Keine medizinische Diagnose.", style = Type.small, color = p.muted)
        footer()
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

/**
 * Eine Kachel pro Messwert. Solange weniger als 4 Messtage vorliegen, zeigen
 * vier Punkte, wie weit dein persönlicher Schnitt schon aufgebaut ist.
 */
@Composable
private fun Tile(label: String, data: List<DayValue>, unit: String, fmt: String, color: Color, modifier: Modifier, trend: Boolean = true) {
    val p = LocalPalette.current
    val need = 4
    Column(
        modifier.fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(p.track.copy(alpha = .45f)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
            Spacer(Modifier.width(8.dp))
            Text(label, style = Type.small, color = p.ink)
        }
        Spacer(Modifier.height(10.dp))
        if (data.isEmpty()) {
            Text("–", style = Type.numeral.copy(fontSize = 40.sp, lineHeight = 40.sp), color = p.muted)
            Spacer(Modifier.weight(1f))
            Text("Keine Werte", style = Type.small, color = p.muted)
            return@Column
        }
        val last = data.last().value
        Big(fmt.format(last * reveal()), unit, 40)
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        if (!trend) return@Column
        val before = data.dropLast(1)
        if (before.size < need - 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(need) { i ->
                    Canvas(Modifier.size(7.dp)) { drawCircle(if (i < data.size) color else p.track) }
                }
                Spacer(Modifier.width(4.dp))
                Text("Basis ${data.size}/$need", style = Type.small, color = p.muted)
            }
        } else {
            val avg = before.map { it.value }.average()
            val delta = last - avg
            Spark(data, color, Modifier.fillMaxWidth().height(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                if (fmt.format(abs(delta)).trimStart('0', '.').isEmpty()) "wie dein Schnitt"
                else "${if (delta > 0) "+" else "−"}${fmt.format(abs(delta))} zum Schnitt",
                style = Type.small, color = p.muted,
            )
        }
    }
}
