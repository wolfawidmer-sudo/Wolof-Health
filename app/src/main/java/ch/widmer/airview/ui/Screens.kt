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

/** Höhe der schwebenden Leiste unten inkl. Abstand. */
val BarSpace = 68.dp + 24.dp

/**
 * Eine Seite füllt genau den Platz zwischen Statusleiste und Taskleiste.
 * Diagramme nehmen per weight() den Rest – nichts scrollt, nichts bleibt leer.
 */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val h = maxHeight
        // verticalScroll nur, damit «zum Aktualisieren ziehen» funktioniert
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().height(h).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = BarSpace + nav + 12.dp)) {
                content()
            }
        }
    }
}

@Composable
fun Section(title: String, aside: String? = null) {
    val p = LocalPalette.current
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
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
        Big(value, unit, size = 30)
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
    Page {
        Text(today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)), style = Type.title, color = p.muted)
        SourceLine(s)
        Spacer(Modifier.height(16.dp))
        RingsSection(s.rings, goals) { goals = it }

        Section("Dein Tag")
        DayStrip(today, s.hrToday, s.sleep, s.workouts, Modifier.fillMaxWidth().weight(1f))

        Spacer(Modifier.height(16.dp))
        val night = s.sleep.lastOrNull()?.takeIf { it.date == today }
        Row(Modifier.fillMaxWidth()) {
            Stat("km", "%.1f".format(s.distanceM / 1000 * r), null, Modifier.weight(1f))
            Stat("Schlaf", night?.let { dur((it.asleepMinutes * r).toLong()) } ?: "–", null, Modifier.weight(1f))
            Stat("Ruhepuls", s.restingHr.lastOrNull()?.let { (it.value * r).roundToInt().toString() } ?: "–", null, Modifier.weight(1f))
        }
    }
}

// ---------- Fitness ----------

@Composable
fun FitnessScreen(s: Snapshot) {
    val p = LocalPalette.current
    val goal = GoalStore.load(androidx.compose.ui.platform.LocalContext.current).steps.toDouble()
    Page {
        val avg = s.stepsWeek.map { it.value }.average()
        Section("Schritte", if (!avg.isNaN()) "Schnitt ${avg.grouped()}" else null)
        if (s.stepsWeek.isNotEmpty()) DayBars(s.stepsWeek, p.move, goal, Modifier.fillMaxWidth().weight(1.2f))
        else Spacer(Modifier.weight(1.2f))

        Section("Kalorien", "kcal")
        if (s.kcalWeek.any { it.value > 0 }) DayBars(s.kcalWeek, p.awake, modifier = Modifier.fillMaxWidth().weight(1f))
        else Spacer(Modifier.weight(1f))

        Section("Trainings", s.vo2max?.let { "VO₂max %.0f".format(it) })
        if (s.workouts.isEmpty()) Note("Keine in den letzten 30 Tagen")
        s.workouts.take(3).forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(p.move) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = Type.title.copy(fontSize = 16.sp, lineHeight = 20.sp), color = p.ink)
                    Text(
                        listOfNotNull(
                            w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d. MMM, HH:mm", DE)),
                            w.distanceM?.takeIf { it > 50 }?.let { "%.1f km".format(it / 1000) },
                            w.avgHr?.let { "$it bpm" },
                        ).joinToString(", "),
                        style = Type.small, color = p.muted, maxLines = 1,
                    )
                }
                Big(dur(Duration.between(w.start, w.end).toMinutes()), null, 26)
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
            Note("Noch keine Nacht erfasst")
            return@Page
        }
        Text("Nacht auf ${n.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE))}", style = Type.title, color = p.muted)
        Spacer(Modifier.height(4.dp))
        val inBed = Duration.between(n.start, n.end).toMinutes()
        Row(verticalAlignment = Alignment.Bottom) {
            Big(dur((n.asleepMinutes * reveal()).toLong()), "h", 88, color = p.ink)
            Spacer(Modifier.weight(1f))
            Text("${n.asleepMinutes * 100 / inBed.coerceAtLeast(1)} % im Bett", style = Type.small, color = p.muted,
                modifier = Modifier.padding(bottom = 14.dp))
        }

        Section("Phasen")
        Hypnogram(n, Modifier.fillMaxWidth().weight(1.3f))
        if (n.stages.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            StageRows(n)
        }
        if (s.sleep.size > 1) {
            Section("Dauer", "Schnitt ${dur(s.sleep.map { it.asleepMinutes }.average().toLong())} h")
            DayBars(s.sleep.takeLast(14).map { DayValue(it.date, it.asleepMinutes / 60.0) }, p.sleep, goal = 8.0,
                modifier = Modifier.fillMaxWidth().weight(1f), format = { "${dur((it * 60).toLong())} h" })
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
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(
            latest?.let { if (it == LocalDate.now()) "Letzte Nacht" else "Nacht auf " + it.format(DateTimeFormatter.ofPattern("EEEE", DE)) }
                ?: "Noch keine Nacht",
            style = Type.numeral.copy(fontSize = 40.sp, lineHeight = 44.sp), color = p.ink,
        )
        Section("Herz")
        TileRow(Modifier.weight(1f)) {
            Tile("Ruhepuls", s.restingHr, "bpm", "%.0f", p.heart, Modifier.weight(1f))
            Tile("HRV", s.hrv, "ms", "%.0f", p.heart, Modifier.weight(1f))
        }
        Section("Atmung")
        TileRow(Modifier.weight(1f)) {
            Tile("Sauerstoff", s.spo2, "%", "%.1f", p.sleep, Modifier.weight(1f))
            Tile("Atemfrequenz", s.respRate, "/min", "%.1f", p.sleep, Modifier.weight(1f))
        }
        s.weightKg?.let {
            Spacer(Modifier.height(12.dp))
            TileRow(Modifier.weight(.6f)) { Tile("Gewicht", listOf(DayValue(LocalDate.now(), it)), "kg", "%.1f", p.move, Modifier.weight(1f), trend = false) }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keine Diagnose", style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
            footer()
        }
    }
}

@Composable
private fun TileRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

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
        if (!trend) return@Column
        Spacer(Modifier.height(12.dp))
        val before = data.dropLast(1)
        if (before.size < need - 1) {
            Spacer(Modifier.weight(1f))
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
            Spark(data, color, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 28.dp, max = 120.dp).fillMaxHeight())
            Spacer(Modifier.height(6.dp))
            Text(
                if (fmt.format(abs(delta)).trimStart('0', '.').isEmpty()) "wie dein Schnitt"
                else "${if (delta > 0) "+" else "−"}${fmt.format(abs(delta))} zum Schnitt",
                style = Type.small, color = p.muted,
            )
        }
    }
}
