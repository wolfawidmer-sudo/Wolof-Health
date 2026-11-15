package ch.widmer.airview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import ch.widmer.airview.data.*
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
import ch.widmer.airview.data.GoalStore
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
fun Group(title: String? = null, aside: String? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier)
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
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
fun Stat(label: String, value: String, unit: String?, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Column(if (onClick != null) modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick) else modifier) {
        Big(value, unit, size = 30, color = color)
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
/** Zeile unter dem Datum: Datenquellen, antippen öffnet die Einstellungen. */
@Composable
fun SourceLine(s: Snapshot) {
    val p = LocalPalette.current
    val open = LocalOpenSheet.current
    val active = s.sources.filter { s.activeSources.isEmpty() || it.pkg in s.activeSources }
    val needsProfile = !s.profile.confirmed
    val label = when {
        needsProfile -> "Profil ergänzen für genaue Kalorien"
        s.sources.isEmpty() -> "Keine Datenquellen"
        s.activeSources.isEmpty() -> "Daten aus allen Apps"
        active.isEmpty() -> "Keine Quelle gewählt"
        else -> active.joinToString(" und ") { it.label }
    }
    Text(
        label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = Type.small.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = if (needsProfile) p.awake else p.muted,
        modifier = Modifier.clickable { open(Sheet.Settings) }.padding(vertical = 4.dp),
    )
}

// ---------- Heute ----------
// Hauptelement: die Ringe. Antippen öffnet die Details, alles darunter bleibt kompakt.

@Composable
fun TodayScreen(s: Snapshot) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val r = reveal()
    val open = LocalOpenSheet.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var goals by remember(s) { mutableStateOf(GoalStore.load(ctx)) }
    Page { avail ->
        // feste Teile: Kopf 56, Werte 64, Woche 62, Tag 30+170, Zahlen 58, Abstände ~70
        val ring = (avail - 510.dp).coerceIn(150.dp, 280.dp)
        Column {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)), style = Type.title, color = p.muted)
            SourceLine(s)
        }
        Column { RingsSection(s.rings, goals, ring) { goals = it } }
        Group("Dein Tag") {
            DayStrip(today, s.hrToday, s.sleep, s.workouts, Modifier.fillMaxWidth().height(170.dp))
        }
        val night = s.sleep.lastOrNull()?.takeIf { it.date == today }
        val rec = s.recoveryToday
        Row(Modifier.fillMaxWidth()) {
            Stat("km", "%.1f".format(s.distanceM / 1000 * r), null, Modifier.weight(1f), onClick = { open(Sheet.Activity) })
            Stat("Schlaf", night?.let { dur((it.asleepMinutes * r).toLong()) } ?: "–", null, Modifier.weight(1f), onClick = { open(Sheet.SleepScore) })
            Stat(rec?.label ?: "Erholung", rec?.let { (it.score * r).roundToInt().toString() } ?: "–", null, Modifier.weight(1f),
                color = if (rec != null) p.heart else p.ink, onClick = { open(Sheet.Recovery) })
        }
    }
}

// ---------- Fitness ----------
// Hauptelement: die Belastung der Woche, nach Pulszonen gestapelt. Darunter Schritte und Trainings.

@Composable
fun FitnessScreen(s: Snapshot) {
    val p = LocalPalette.current
    val open = LocalOpenSheet.current
    val goal = GoalStore.load(androidx.compose.ui.platform.LocalContext.current).steps.toDouble()
    Page { avail ->
        val loadH = (avail * 0.38f).coerceIn(190.dp, 280.dp)
        val stepsH = 150.dp
        val rowH = 54.dp
        val fit = ((avail - loadH - stepsH - 34.dp * 3 - 60.dp).value / rowH.value).toInt().coerceIn(1, 5)

        val l = load(s.days)
        Group("Belastung", l?.short, onClick = { open(Sheet.Load) }) {
            LoadBars(s.days7, loadBand(s.days), Modifier.fillMaxWidth().height(loadH))
        }
        val avg = s.stepsWeek.map { it.value }.average()
        Group("Schritte", if (!avg.isNaN()) "Schnitt ${avg.grouped()}" else null, onClick = { open(Sheet.Activity) }) {
            if (s.stepsWeek.isNotEmpty()) DayBars(s.stepsWeek, p.move, goal, Modifier.fillMaxWidth().height(stepsH))
        }
        Group("Trainings", s.vo2max?.let { "VO₂max %.0f".format(it) }) {
            if (s.workouts.isEmpty()) Note("Keine in den letzten 30 Tagen")
            val zc = zoneColors()
            s.workouts.take(fit).forEachIndexed { i, w ->
                Row(
                    Modifier.fillMaxWidth().height(rowH).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { open(Sheet.Workout(i)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Zonenverteilung als kleiner senkrechter Streifen statt eines Punkts
                    Canvas(Modifier.width(6.dp).height(34.dp)) {
                        val tot = w.zoneMin.sum()
                        if (tot == 0) { drawRoundRect(p.move, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); return@Canvas }
                        var y = size.height
                        w.zoneMin.forEachIndexed { z, m -> val h = size.height * m / tot; drawRect(zc[z], Offset(0f, y - h), Size(size.width, h)); y -= h }
                    }
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
// Hauptelement: das Hypnogramm (mit Höchstmass). Schlafwert neben der Dauer, Rhythmus als schwebende Balken.

@Composable
fun SleepScreen(s: Snapshot) {
    val p = LocalPalette.current
    val open = LocalOpenSheet.current
    val n = s.sleep.lastOrNull()
    Page { avail ->
        if (n == null) {
            Group("Schlaf") { Note("Noch keine Nacht erfasst") }
            return@Page
        }
        val many = true   // 14 feste Plätze: auch mit wenigen Nächten ein ruhiges Bild
        val fixed = 30.dp + 100.dp + 40.dp + 86.dp + (if (many) 210.dp else 0.dp) + 60.dp
        val hypH = (avail - fixed).coerceIn(150.dp, 240.dp)
        val score = s.sleepScores[n.date]
        Column {
            Text("Nacht auf ${n.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE))}", style = Type.title, color = p.muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Big(dur((n.asleepMinutes * reveal()).toLong()), "h", 88, color = p.ink)
                Spacer(Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(bottom = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { open(Sheet.SleepScore) }.padding(4.dp),
                ) {
                    if (score != null) Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(score.total * reveal()).roundToInt()}", style = Type.numeral.copy(fontSize = 40.sp, lineHeight = 40.sp), color = p.sleep)
                        Text(" ${score.label}", style = Type.small, color = p.sleep, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Text("${hhmm(n.start)} bis ${hhmm(n.end)}", style = Type.small, color = p.muted)
                }
            }
        }
        Group("Phasen") { Hypnogram(n, Modifier.fillMaxWidth().height(hypH)) }
        if (n.stages.isNotEmpty()) StageSummary(n)
        if (many) Group("Rhythmus", "Schnitt ${dur(s.sleep.takeLast(14).map { it.asleepMinutes }.average().toLong())} h") {
            SleepWindows(s.sleep.takeLast(14), Modifier.fillMaxWidth().height(170.dp))
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
// Hauptelement: die Erholung als Bogen. Darunter die Nachtwerte mit persönlichem Normalbereich.

@Composable
fun HealthScreen(s: Snapshot, footer: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    val open = LocalOpenSheet.current
    val vs = vitals(s)
    val off = outOfRangeToday(s)
    Page { avail ->
        val rowsH = vs.size * 92 + 60
        val gauge = (avail - rowsH.dp - 40.dp).coerceIn(130.dp, 230.dp)
        Box(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).clickable { open(Sheet.Recovery) },
            contentAlignment = Alignment.Center,
        ) {
            val rec = s.recoveryToday
            if (rec != null) Gauge(rec.score, rec.label, gauge, p.heart)
            else {
                val n = minOf(s.hrv.size, s.restingHr.size)
                BaselineGauge(n, gauge, p.heart, if (n < MIN_BASELINE) "Nächte bis zur Erholung" else "wartet auf die Nachtwerte")
            }
        }
        if (off.size >= 2) Text("${off.joinToString(" und ") { it.label }} ungewohnt", style = Type.small, color = p.awake)
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (vs.isEmpty()) Note("Trag das Band in der Nacht, dann erscheinen hier deine Werte.")
            vs.forEach { v ->
                MetricRow(v, if (v.key == "rhr" || v.key == "hrv") p.heart else p.sleep, Modifier.weight(1f).clickable { open(Sheet.Vital(v.key)) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keine Diagnose", style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
            footer()
        }
    }
}

/** Platzhalter für die Erholung: die Nächte bis zur ersten Berechnung als Bogen-Segmente. */
@Composable
private fun BaselineGauge(nights: Int, size: androidx.compose.ui.unit.Dp, color: Color, caption: String) {
    val p = LocalPalette.current
    val r = reveal()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.width * .085f
            val tl = Offset(sw / 2, sw / 2); val sz = Size(this.size.width - sw, this.size.height - sw)
            val seg = 270f / MIN_BASELINE
            for (i in 0 until MIN_BASELINE) {
                val on = i < nights
                drawArc(if (on) color.copy(alpha = r) else p.track, 135f + i * seg + 3f, seg - 6f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Butt))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (nights < MIN_BASELINE) "${nights.coerceAtMost(MIN_BASELINE)}/$MIN_BASELINE" else "–",
                style = Type.numeral.copy(fontSize = (size.value * .26f).sp, lineHeight = (size.value * .26f).sp), color = p.ink)
            Text(caption, style = Type.small, color = p.muted)
        }
    }
}

/**
 * Eine Nachtmetrik: links der Wert, rechts der Verlauf mit Normalbereich.
 * Vor 4 Nächten zeigen vier Punkte, wie weit der Verlauf aufgebaut ist.
 */
@Composable
private fun MetricRow(v: Vital, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    val need = 4
    val last = v.last ?: return
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.track))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val num = (maxHeight.value * 0.34f).coerceIn(36f, 72f).toInt()
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Spacer(Modifier.width(8.dp))
                        Text(v.label, style = Type.small, color = p.ink)
                        if (v.inRange == false) Text("  ausserhalb", style = Type.small, color = p.awake)
                    }
                    Big(v.fmt.format(last.value * reveal()), v.unit, num)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(140.dp)) {
                    if (v.values.size < need) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(need) { i -> Canvas(Modifier.size(7.dp)) { drawCircle(if (i < v.values.size) color else p.track) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Verlauf ab $need Nächten", style = Type.small, color = p.muted)
                    } else {
                        RangeChart(v.values.takeLast(30), v.range, color, Modifier.fillMaxWidth().height(44.dp), dots = false)
                        Spacer(Modifier.height(4.dp))
                        Text(v.range?.let { "Bereich ${v.fmt.format(it.low)} bis ${v.fmt.format(it.high)}" } ?: "Bereich ab $MIN_BASELINE Nächten",
                            style = Type.small, color = p.muted)
                    }
                }
            }
        }
    }
}
