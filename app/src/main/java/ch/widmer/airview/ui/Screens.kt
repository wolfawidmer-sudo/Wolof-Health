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
 * Grundsatz: ein Hauptelement pro Seite skaliert mit dem Bildschirm (behält aber seine
 * Proportionen), alles andere hat seine natürliche Grösse. Übriger Platz wird als
 * gleichmässiger Abstand zwischen den Gruppen verteilt – nichts wird verzerrt.
 */
@Composable
fun Page(content: @Composable ColumnScope.(avail: androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val h = maxHeight
        val inner = h - 12.dp - (BarSpace + nav + 16.dp)
        // verticalScroll nur, damit «zum Aktualisieren ziehen» funktioniert
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().height(h).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = BarSpace + nav + 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) { content(inner) }
        }
    }
}

/** Eine zusammengehörige Gruppe: optionaler Titel, dann Inhalt. Abstände entstehen zwischen Gruppen. */
@Composable
fun Group(title: String? = null, aside: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
            if (aside != null) Text(aside, style = Type.small, color = p.muted)
        }
        content()
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
// Hauptelement: die Ringe. Sie wachsen mit dem Bildschirm, alles darunter bleibt kompakt.

@Composable
fun TodayScreen(s: Snapshot) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val r = reveal()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var goals by remember { mutableStateOf(GoalStore.load(ctx)) }
    Page { avail ->
        // feste Teile: Kopf 56, Werte 56, Woche 62, Tag 30+170, Zahlen 58, Abstände ~70
        val ring = (avail - 502.dp).coerceIn(150.dp, 280.dp)
        Column {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)), style = Type.title, color = p.muted)
            SourceLine(s)
        }
        Column { RingsSection(s.rings, goals, ring) { goals = it } }
        Group("Dein Tag") {
            DayStrip(today, s.hrToday, s.sleep, s.workouts, Modifier.fillMaxWidth().height(170.dp))
        }
        val night = s.sleep.lastOrNull()?.takeIf { it.date == today }
        Row(Modifier.fillMaxWidth()) {
            Stat("km", "%.1f".format(s.distanceM / 1000 * r), null, Modifier.weight(1f))
            Stat("Schlaf", night?.let { dur((it.asleepMinutes * r).toLong()) } ?: "–", null, Modifier.weight(1f))
            Stat("Ruhepuls", s.restingHr.lastOrNull()?.let { (it.value * r).roundToInt().toString() } ?: "–", null, Modifier.weight(1f))
        }
    }
}

// ---------- Fitness ----------
// Hauptelement: die Schrittwoche. Kalorien kompakt; Trainings so viele, wie Platz haben.

@Composable
fun FitnessScreen(s: Snapshot) {
    val p = LocalPalette.current
    val goal = GoalStore.load(androidx.compose.ui.platform.LocalContext.current).steps.toDouble()
    Page { avail ->
        val stepsH = (avail * 0.40f).coerceIn(200.dp, 290.dp)
        val kcalH = 150.dp
        val rowH = 54.dp
        val fit = ((avail - stepsH - kcalH - 34.dp * 3 - 60.dp).value / rowH.value).toInt().coerceIn(1, 6)

        val avg = s.stepsWeek.map { it.value }.average()
        Group("Schritte", if (!avg.isNaN()) "Schnitt ${avg.grouped()}" else null) {
            if (s.stepsWeek.isNotEmpty()) DayBars(s.stepsWeek, p.move, goal, Modifier.fillMaxWidth().height(stepsH))
        }
        Group("Kalorien", "kcal") {
            if (s.kcalWeek.any { it.value > 0 }) DayBars(s.kcalWeek, p.awake, modifier = Modifier.fillMaxWidth().height(kcalH))
        }
        Group("Trainings", s.vo2max?.let { "VO₂max %.0f".format(it) }) {
            if (s.workouts.isEmpty()) Note("Keine in den letzten 30 Tagen")
            s.workouts.take(fit).forEach { w ->
                Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(p.move) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.title, style = Type.title.copy(fontSize = 16.sp, lineHeight = 20.sp), color = p.ink)
                        Text(
                            listOfNotNull(
                                w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d. MMM, HH:mm", DE)),
                                w.distanceM?.takeIf { it > 50 }?.let { "%.1f km".format(it / 1000) },
                            ).joinToString(", "),
                            style = Type.small, color = p.muted, maxLines = 1,
                        )
                    }
                    Big(dur(Duration.between(w.start, w.end).toMinutes()), null, 26)
                }
            }
        }
    }
}

// ---------- Schlaf ----------
// Hauptelement: das Hypnogramm (mit Höchstmass). Phasen als ein geteilter Balken.

@Composable
fun SleepScreen(s: Snapshot) {
    val p = LocalPalette.current
    val n = s.sleep.lastOrNull()
    Page { avail ->
        if (n == null) {
            Group("Schlaf") { Note("Noch keine Nacht erfasst") }
            return@Page
        }
        val many = s.sleep.size > 1
        val fixed = 30.dp + 100.dp + 40.dp + 86.dp + (if (many) 210.dp else 0.dp) + 60.dp
        val hypH = (avail - fixed).coerceIn(150.dp, 240.dp)
        Column {
            Text("Nacht auf ${n.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE))}", style = Type.title, color = p.muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Big(dur((n.asleepMinutes * reveal()).toLong()), "h", 88, color = p.ink)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 12.dp)) {
                    Text("${hhmm(n.start)} bis ${hhmm(n.end)}", style = Type.body, color = p.ink)
                    val inBed = Duration.between(n.start, n.end).toMinutes()
                    Text("${n.asleepMinutes * 100 / inBed.coerceAtLeast(1)} % im Bett", style = Type.small, color = p.muted)
                }
            }
        }
        Group("Phasen") { Hypnogram(n, Modifier.fillMaxWidth().height(hypH)) }
        if (n.stages.isNotEmpty()) StageSummary(n)
        if (many) Group("Dauer", "Schnitt ${dur(s.sleep.map { it.asleepMinutes }.average().toLong())} h") {
            DayBars(s.sleep.takeLast(14).map { DayValue(it.date, it.asleepMinutes / 60.0) }, p.sleep, goal = 8.0,
                modifier = Modifier.fillMaxWidth().height(170.dp), format = { "${dur((it * 60).toLong())} h" })
        }
    }
}

/** Alle Phasen in einem Balken, darunter vier Spalten mit Dauer und Anteil. */
@Composable
private fun StageSummary(n: SleepNight) {
    val p = LocalPalette.current
    val r = reveal()
    val rows = listOf(
        Triple("Wach", n.minutesIn(S.STAGE_TYPE_AWAKE, S.STAGE_TYPE_AWAKE_IN_BED, S.STAGE_TYPE_OUT_OF_BED), p.awake),
        Triple("REM", n.minutesIn(S.STAGE_TYPE_REM), p.sleepLight),
        Triple("Leicht", n.minutesIn(S.STAGE_TYPE_LIGHT, S.STAGE_TYPE_SLEEPING), p.sleepLight.copy(alpha = .7f)),
        Triple("Tief", n.minutesIn(S.STAGE_TYPE_DEEP), p.sleep),
    )
    val total = rows.sumOf { it.second }.coerceAtLeast(1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) {
            drawRect(p.track)
            var x = 0f
            rows.forEach { (_, m, c) ->
                val w = size.width * m / total * r
                drawRect(c, Offset(x, 0f), Size(w, size.height))
                x += w + 2f
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            rows.forEach { (label, m, c) ->
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(7.dp)) { drawCircle(c) }
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = Type.small, color = p.muted)
                    }
                    Text(dur(m), style = Type.numeral.copy(fontSize = 22.sp, lineHeight = 26.sp), color = p.ink)
                    Text("${m * 100 / total} %", style = Type.small, color = p.muted)
                }
            }
        }
    }
}

// ---------- Gesundheit ----------
// Messreihen statt Kacheln: jede Zeile ein Instrument, gleichmässig über die Höhe verteilt.

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
        // nur Werte zeigen, die das Band wirklich liefert
        val rows = buildList {
            if (s.restingHr.isNotEmpty()) add(Triple("Ruhepuls", s.restingHr, Triple("bpm", "%.0f", p.heart)))
            if (s.hrv.isNotEmpty()) add(Triple("HRV", s.hrv, Triple("ms", "%.0f", p.heart)))
            if (s.spo2.isNotEmpty()) add(Triple("Sauerstoff", s.spo2, Triple("%", "%.1f", p.sleep)))
            if (s.respRate.isNotEmpty()) add(Triple("Atemfrequenz", s.respRate, Triple("/min", "%.1f", p.sleep)))
            s.weightKg?.let { add(Triple("Gewicht", listOf(DayValue(LocalDate.now(), it)), Triple("kg", "%.1f", p.move))) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp)) {
            if (rows.isEmpty()) Note("Trag das Band in der Nacht, dann erscheinen hier deine Werte.")
            rows.forEach { (label, data, f) ->
                MetricRow(label, data, f.first, f.second, f.third, trend = label != "Gewicht", modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keine Diagnose", style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
            footer()
        }
    }
}

/**
 * Eine Messreihe: links Wert, rechts Verlauf. Solange weniger als 4 Messtage vorliegen,
 * zeigen vier Punkte, wie weit dein persönlicher Schnitt aufgebaut ist.
 */
@Composable
private fun MetricRow(label: String, data: List<DayValue>, unit: String, fmt: String, color: Color, trend: Boolean = true, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val need = 4
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.track))
        // Inhalt mittig zwischen den Trennlinien
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        // Zahl wächst mit der Zeilenhöhe: wenige Werte = grössere Zahlen statt Leerraum
        val num = (maxHeight.value * 0.34f).coerceIn(44f, 88f).toInt()
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = Type.small, color = p.ink)
                }
                if (data.isEmpty()) Text("–", style = Type.numeral.copy(fontSize = num.sp, lineHeight = (num * 1.05).sp), color = p.muted)
                else Big(fmt.format(data.last().value * reveal()), unit, num)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(132.dp).padding(bottom = 6.dp)) {
                when {
                    data.isEmpty() -> Text("Keine Werte", style = Type.small, color = p.muted)
                    !trend -> {}
                    data.size < need -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(need) { i -> Canvas(Modifier.size(7.dp)) { drawCircle(if (i < data.size) color else p.track) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Verlauf ab $need Nächten", style = Type.small, color = p.muted)
                    }
                    else -> {
                        val before = data.dropLast(1).map { it.value }.average()
                        val delta = data.last().value - before
                        Spark(data, color, Modifier.fillMaxWidth().height(34.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (fmt.format(abs(delta)).trimStart('0', '.').isEmpty()) "wie dein Schnitt"
                            else "${if (delta > 0) "+" else "−"}${fmt.format(abs(delta))} zum Schnitt",
                            style = Type.small, color = p.muted,
                        )
                    }
                }
            }
        }
    }
        }
}
