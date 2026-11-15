package ch.widmer.airview.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.widmer.airview.data.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Welche Detailansicht offen ist. Detailansichten liegen über der Seite und dürfen scrollen. */
sealed interface Sheet {
    data class Ring(val index: Int) : Sheet
    data object Recovery : Sheet
    data object SleepScore : Sheet
    data class Vital(val key: String) : Sheet
    data object Load : Sheet
    data object Activity : Sheet
    data class Workout(val index: Int) : Sheet
    data object Settings : Sheet
    data object Month : Sheet
}

val LocalOpenSheet = compositionLocalOf<(Sheet) -> Unit> { {} }
/** Öffnet die Health-Connect-Freigabe (alle Rechte inkl. Verlauf und Hintergrund). */
val LocalRequestAccess = compositionLocalOf<() -> Unit> { {} }

private val DE = Locale.GERMAN
private fun Number.grouped(): String = "%,d".format(Locale.forLanguageTag("de-CH"), toLong())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(sheet: Sheet?, s: Snapshot, onClose: () -> Unit) {
    if (sheet == null) return
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.bar, contentColor = p.ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(p.track)) },
    ) {
        // Die Animationen der Detailansicht laufen sofort voll
        CompositionLocalProvider(LocalReveal provides 1f) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                SheetContent(sheet, s, onClose)
            }
        }
    }
}

@Composable
fun SheetContent(sheet: Sheet, s: Snapshot, onClose: () -> Unit = {}) {
    when (sheet) {
        is Sheet.Ring -> RingSheet(s, sheet.index)
        Sheet.Recovery -> RecoverySheet(s)
        Sheet.SleepScore -> SleepScoreSheet(s)
        is Sheet.Vital -> VitalSheet(s, sheet.key)
        Sheet.Load -> LoadSheet(s)
        Sheet.Activity -> ActivitySheet(s)
        is Sheet.Workout -> WorkoutSheet(s, sheet.index)
        Sheet.Settings -> SettingsSheet(s, onClose)
        Sheet.Month -> MonthSheet(s)
    }
}

// ---------- Bausteine ----------

@Composable
private fun Head(title: String, sub: String? = null) {
    val p = LocalPalette.current
    Text(title, style = Type.numeral.copy(fontSize = 34.sp, lineHeight = 38.sp), color = p.ink, modifier = Modifier.padding(top = 8.dp))
    if (sub != null) Text(sub, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun SubHead(t: String) {
    Text(t, style = Type.title, color = LocalPalette.current.ink, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
}

@Composable
private fun Fine(t: String) {
    Text(t, style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun KeyValue(k: String, v: String, color: Color = LocalPalette.current.ink) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Type.body, color = p.muted, modifier = Modifier.weight(1f))
        Text(v, style = Type.title.copy(fontSize = 16.sp), color = color)
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(p.track).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(label, style = Type.title.copy(fontSize = 15.sp), color = p.ink) }
}

// ---------- Ringe ----------

@Composable
private fun RingSheet(s: Snapshot, index: Int) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var goals by remember { mutableStateOf(GoalStore.load(ctx)) }
    var editing by remember { mutableStateOf(false) }
    val t = s.today
    when (index) {
        0 -> {
            Head("Schritte", "${t.steps.grouped()} von ${goals.steps.grouped()}")
            HourBars(s.hours.steps.map { it.toDouble() }, p.move, Modifier.fillMaxWidth().height(150.dp))
            KeyValue("Distanz", "%.1f km".format(t.distanceM / 1000))
            KeyValue("Stockwerke", s.floors.roundToInt().toString())
        }
        1 -> {
            Head("Bewegen", "${t.activeKcal.roundToInt().grouped()} von ${goals.moveKcal.grouped()} kcal")
            HourBars(s.hours.activeKcal.toList(), p.awake, Modifier.fillMaxWidth().height(150.dp))
            KeyValue("Gesamt heute", "${t.totalKcal.roundToInt().grouped()} kcal")
            KeyValue("Grundumsatz pro Tag", "${s.profile.bmrPerDay.roundToInt().grouped()} kcal")
            if (t.activeEstimated) Fine("Google Health gibt keine aktiven Kalorien weiter. Wolof Health schätzt sie pro Stunde: Gesamtkalorien minus dein Grundumsatz (aus Alter, Geschlecht, Grösse und Gewicht).")
        }
        else -> {
            Head("Trainieren", "${t.trainingMin} von ${goals.exerciseMin} min")
            HourBars(s.hours.trainingMin.map { it.toDouble() }, p.heart, Modifier.fillMaxWidth().height(150.dp))
            SubHead("Pulszonen heute")
            ZoneRows(t.zoneMin, s)
            Fine("Zählt jede Minute mit mindestens 100 Schritten, mit Puls ab Zone 2 oder in einem Training.")
        }
    }
    Action("Ziele ändern") { editing = true }
    val refresh = LocalRefresh.current
    if (editing) GoalsDialog(goals, onDismiss = { editing = false }) { g -> goals = g; GoalStore.save(ctx, g); editing = false; refresh() }
}

@Composable
fun ZoneRows(zm: IntArray, s: Snapshot) {
    val p = LocalPalette.current
    val zc = zoneColors()
    val b = s.zoneBounds
    val total = zm.sum().coerceAtLeast(1)
    ZONE_NAMES.forEachIndexed { i, name ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(zc[i]))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(96.dp)) {
                Text(name, style = Type.body, color = p.ink)
                Text("ab ${b[i].roundToInt()} bpm", style = Type.small, color = p.muted)
            }
            Box(Modifier.weight(1f)) { Meter(zm[i] / total.toFloat(), zc[i]) }
            Text("${zm[i]} min", style = Type.body, color = p.ink, modifier = Modifier.width(64.dp).padding(start = 10.dp))
        }
    }
}

// ---------- Erholung ----------

@Composable
private fun RecoverySheet(s: Snapshot) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val r = s.recoveryToday
    if (r == null) {
        val n = minOf(s.hrv.size, s.restingHr.size)
        Head("Erholung", if (n < MIN_BASELINE) "Ab $MIN_BASELINE Nächten, bisher $n" else "Die Nachtwerte von heute fehlen noch")
        Text(
            if (n < MIN_BASELINE) "Die Erholung vergleicht HRV, Ruhepuls und Atemfrequenz der letzten Nacht mit deinem eigenen Schnitt. Dafür braucht es mindestens sieben Nächte."
            else "Google Health schreibt die Nachtwerte oft erst eine Stunde nach dem Aufwachen. Danach erscheint die Erholung.",
            style = Type.body, color = p.muted,
        )
    } else {
        Head("Erholung ${r.score}", r.label)
        SubHead("Woraus sie besteht")
        r.parts.forEach { part ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(part.label, style = Type.body, color = p.ink)
                    val f = if (part.unit == "/min") "%.1f" else "%.0f"
                    Text("${f.format(part.value)}${if (part.unit.isEmpty()) "" else " " + part.unit}, Schnitt ${f.format(part.baseline)}", style = Type.small, color = p.muted)
                }
                EffectBar(part.effect, p.heart)
            }
        }
        val hist = s.recoveries.entries.sortedBy { it.key }.takeLast(14)
        if (hist.size > 1) {
            SubHead("Letzte ${hist.size} Tage")
            DayBars(hist.map { DayValue(it.key, it.value.score.toDouble()) }, p.heart, modifier = Modifier.fillMaxWidth().height(170.dp))
        }
    }
    // Gewohnheiten von gestern Abend
    val yesterday = LocalDate.now().minusDays(1)
    var journal by remember { mutableStateOf(JournalStore.load(ctx)) }
    SubHead("Gestern Abend")
    JournalStore.HABITS.forEach { h ->
        val v = journal[yesterday]?.get(h)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
            listOf(true to "Ja", false to "Nein").forEach { (value, label) ->
                val on = v == value
                Box(
                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (on) p.heart else p.track)
                        .clickable { JournalStore.set(ctx, yesterday, h, if (on) null else value); journal = JournalStore.load(ctx) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, style = Type.small, color = if (on) p.bg else p.ink) }
            }
        }
    }
    val ins = habitInsights(journal, s.recoveries.mapValues { it.value.score }, LocalDate.now())
    if (ins.isEmpty()) Fine("Ab je fünf Ja- und Nein-Tagen zeigt Wolof Health, wie eine Gewohnheit mit deiner Erholung am nächsten Morgen zusammenhängt.")
    else {
        SubHead("Was du bisher siehst")
        ins.forEach { i ->
            KeyValue("Mit «${i.habit}»", "%s%.0f Punkte".format(if (i.diff >= 0) "+" else "−", abs(i.diff)), if (i.diff >= 0) p.ink else p.awake)
        }
        Fine("Zusammenhang, kein Beweis für eine Ursache.")
    }
    Fine("Eigene Näherung aus öffentlich beschriebenen Prinzipien, nicht der Wert von Google Health.")
}

// ---------- Schlafwert ----------

@Composable
private fun SleepScoreSheet(s: Snapshot) {
    val p = LocalPalette.current
    val n = s.sleep.lastOrNull() ?: return
    val sc = s.sleepScores[n.date] ?: return
    Head("Schlafwert ${sc.total}", sc.label)
    PartBar("Dauer", sc.duration, sc.durationMax, p.sleep, "${dur(n.asleepMinutes)} h von ${dur(s.profile.sleepGoalMin.toLong())} h Ziel")
    if (sc.rhythm != null) PartBar("Regelmässigkeit", sc.rhythm, sc.rhythmMax, p.sleep,
        sc.bedDeviationMin?.let { "Bettzeit $it min neben deinem Üblichen" })
    else Fine("Regelmässigkeit zählt ab drei Nächten.")
    PartBar("Ruhe", sc.calm, sc.calmMax, p.sleep, "${sc.awakeMin} min wach, ${sc.wakeups}× aufgewacht")

    val need = sleepNeed(s.profile.sleepGoalMin, s.sleep, s.days)
    SubHead("Heute Nacht")
    KeyValue("Schlafbedarf", "${dur(need.needMin.toLong())} h")
    if (need.debtMin > 0) KeyValue("Schlafschuld (3 Nächte)", "${dur(need.debtMin.toLong())} h", p.awake)
    if (need.strainAdjMin != 0) KeyValue("Wegen Belastung heute", "%+d min".format(need.strainAdjMin))
    bedtimeFor(s)?.let { KeyValue("Ins Bett um", "%d:%02d".format(it.hour, it.minute), p.sleep) }
    Fine("Aufteilung wie bei Apple: Dauer 50, Regelmässigkeit 30, Ruhe 20 Punkte. Der Bedarf ist eine eigene einfache Regel.")
}

// ---------- Nachtwerte ----------

@Composable
private fun VitalSheet(s: Snapshot, key: String) {
    val p = LocalPalette.current
    val v = vitals(s).firstOrNull { it.key == key } ?: return
    val color = if (key == "rhr" || key == "hrv") p.heart else p.sleep
    val last = v.last ?: return
    Head("${v.label} ${v.fmt.format(last.value)} ${v.unit}", last.date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)))
    RangeChart(v.values.takeLast(60), v.range, color, Modifier.fillMaxWidth().height(200.dp))
    Spacer(Modifier.height(10.dp))
    v.range?.let {
        KeyValue("Dein Bereich", "${v.fmt.format(it.low)} bis ${v.fmt.format(it.high)} ${v.unit}")
        KeyValue("Dein Schnitt", "${v.fmt.format(it.mean)} ${v.unit}")
        KeyValue("Letzte Nacht", if (it.contains(last.value)) "im Bereich" else "ausserhalb", if (it.contains(last.value)) p.ink else p.awake)
    } ?: Fine("Den Bereich gibt es ab $MIN_BASELINE Nächten. Bisher ${v.values.size}.")
    trend30(v.values, LocalDate.now())?.let { t ->
        KeyValue("30 Tage gegen die 30 davor", "%+.1f %%".format(t.deltaPct))
    }
    Fine("Bereich: dein Schnitt der letzten bis zu 30 Nächte ± 2 Standardabweichungen. Hohle Punkte liegen ausserhalb.")
}

// ---------- Belastung ----------

@Composable
private fun LoadSheet(s: Snapshot) {
    val p = LocalPalette.current
    val l = load(s.days)
    Head("Belastung", l?.label ?: "Vergleich ab 28 Tagen mit Pulsdaten")
    LoadBars(s.lastDays(14), loadBand(s.days), Modifier.fillMaxWidth().height(200.dp))
    l?.let {
        KeyValue("Schnitt letzte 7 Tage", "%.0f".format(it.acute))
        KeyValue("Schnitt letzte 28 Tage", "%.0f".format(it.chronic))
    }
    SubHead("Diese Woche in den Zonen")
    val week = IntArray(5).also { a -> s.days.takeLast(7).forEach { d -> d.zoneMin.forEachIndexed { i, m -> a[i] += m } } }
    ZoneRows(week, s)
    Fine("Belastung = Minuten pro Pulszone × Zonennummer (Edwards). Zonen nach Karvonen aus Ruhepuls ${s.restHrForZones.roundToInt()} und Maximalpuls ${s.profile.maxHr.roundToInt()}. Das Band zeigt deinen üblichen Bereich.")
}

// ---------- Aktivität ----------

@Composable
private fun ActivitySheet(s: Snapshot) {
    val p = LocalPalette.current
    val week = s.days7
    Head("Aktivität", "letzte 7 Tage")
    Text("Aktive Kalorien", style = Type.title, color = p.ink, modifier = Modifier.padding(bottom = 8.dp))
    DayBars(week.map { DayValue(it.date, it.activeKcal) }, p.awake, modifier = Modifier.fillMaxWidth().height(170.dp))
    Spacer(Modifier.height(18.dp))
    Text("Distanz", style = Type.title, color = p.ink, modifier = Modifier.padding(bottom = 8.dp))
    DayBars(week.map { DayValue(it.date, it.distanceM / 1000) }, p.move, modifier = Modifier.fillMaxWidth().height(170.dp),
        format = { "%.1f km".format(it) })
    Spacer(Modifier.height(10.dp))
    KeyValue("Gesamtkalorien heute", "${s.totalKcal.roundToInt().grouped()} kcal")
}

// ---------- Training ----------

@Composable
private fun WorkoutSheet(s: Snapshot, index: Int) {
    val p = LocalPalette.current
    val w = s.workouts.getOrNull(index) ?: return
    val min = Duration.between(w.start, w.end).toMinutes()
    Head(w.title, w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d. MMMM, HH:mm", DE)))
    KeyValue("Dauer", "${dur(min)} h")
    w.distanceM?.takeIf { it > 50 }?.let { KeyValue("Distanz", "%.2f km".format(it / 1000)) }
    w.avgHr?.let { KeyValue("Puls im Schnitt", "$it bpm") }
    KeyValue("Belastung", "%.0f".format(trimp(w.zoneMin)))
    SubHead("Pulszonen")
    ZoneRows(w.zoneMin, s)
}

// ---------- Monatsbericht ----------

@Composable
private fun MonthSheet(s: Snapshot) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val thisStart = today.withDayOfMonth(1)
    val lastStart = thisStart.minusMonths(1)
    fun inMonth(d: LocalDate, start: LocalDate) = d >= start && d < start.plusMonths(1)
    val monthName = { d: LocalDate -> d.month.getDisplayName(java.time.format.TextStyle.FULL, DE) }
    Head("Monatsbericht", "${monthName(thisStart)} bis heute gegen ${monthName(lastStart)}")
    val rows = listOf(
        Triple("Schritte pro Tag", s.days.filter { inMonth(it.date, thisStart) }.map { it.steps.toDouble() }, s.days.filter { inMonth(it.date, lastStart) }.map { it.steps.toDouble() }),
        Triple("Trainingsminuten pro Tag", s.days.filter { inMonth(it.date, thisStart) }.map { it.trainingMin.toDouble() }, s.days.filter { inMonth(it.date, lastStart) }.map { it.trainingMin.toDouble() }),
        Triple("Belastung pro Tag", s.days.filter { inMonth(it.date, thisStart) && it.hrMinutes > 60 }.map { it.trimp }, s.days.filter { inMonth(it.date, lastStart) && it.hrMinutes > 60 }.map { it.trimp }),
        Triple("Schlaf pro Nacht (h)", s.sleep.filter { inMonth(it.date, thisStart) }.map { it.asleepMinutes / 60.0 }, s.sleep.filter { inMonth(it.date, lastStart) }.map { it.asleepMinutes / 60.0 }),
        Triple("Erholung", s.recoveries.filterKeys { inMonth(it, thisStart) }.values.map { it.score.toDouble() }, s.recoveries.filterKeys { inMonth(it, lastStart) }.values.map { it.score.toDouble() }),
        Triple("Ruhepuls", s.restingHr.filter { inMonth(it.date, thisStart) }.map { it.value }, s.restingHr.filter { inMonth(it.date, lastStart) }.map { it.value }),
    )
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(Modifier.weight(1f))
        Text(monthName(lastStart), style = Type.small, color = p.muted, modifier = Modifier.width(70.dp))
        Text(monthName(thisStart), style = Type.small, color = p.ink, modifier = Modifier.width(70.dp))
    }
    rows.forEach { (label, a, b) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
            fun f(v: List<Double>) = if (v.isEmpty()) "–" else if (label.contains("(h)")) dur((v.average() * 60).toLong()) else v.average().roundToInt().grouped()
            Text(f(b), style = Type.body, color = p.muted, modifier = Modifier.width(70.dp))
            Text(f(a), style = Type.title.copy(fontSize = 16.sp), color = p.ink, modifier = Modifier.width(70.dp))
        }
    }
    if (s.days.none { it.date < thisStart }) Fine("Den Vergleich mit dem Vormonat gibt es, sobald Daten aus dem letzten Monat vorliegen.")

}

// ---------- Einstellungen ----------

@Composable
private fun SettingsSheet(s: Snapshot, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val refresh = LocalRefresh.current
    val open = LocalOpenSheet.current
    var profile by remember { mutableStateOf(ProfileStore.load(ctx).let { if (!it.confirmed && s.weightKg != null) it.copy(weightKg = s.weightKg) else it }) }
    var goals by remember { mutableStateOf(GoalStore.load(ctx)) }
    var pick by remember { mutableStateOf(if (s.activeSources.isEmpty()) s.sources.map { it.pkg }.toSet() else s.activeSources) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotif() { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val access = LocalRequestAccess.current
    Head("Einstellungen")
    Action("Monatsbericht ansehen") { open(Sheet.Month) }
    if (s.extrasMissing) {
        SubHead("Zugriff")
        Text("Mit Zugriff auf den Verlauf und im Hintergrund rechnet Wolof Health mit mehr als 30 Tagen und hält Widget und Hinweise aktuell.",
            style = Type.small, color = p.muted)
        Action("Zugriff erweitern") { onClose(); access() }
    }

    SubHead("Profil")
    Text("Für Grundumsatz, Maximalpuls und Pulszonen.", style = Type.small, color = p.muted)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Geschlecht", style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
        listOf(true to "Mann", false to "Frau").forEach { (m, l) ->
            val on = profile.male == m
            Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (on) p.ink else p.track)
                .clickable { profile = profile.copy(male = m) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(l, style = Type.small, color = if (on) p.bg else p.ink)
            }
        }
    }
    Stepper("Alter", "${profile.age} Jahre", p.ink, { profile = profile.copy(age = (profile.age - 1).coerceAtLeast(14)) }, { profile = profile.copy(age = (profile.age + 1).coerceAtMost(100)) })
    Stepper("Grösse", "${profile.heightCm} cm", p.ink, { profile = profile.copy(heightCm = (profile.heightCm - 1).coerceAtLeast(120)) }, { profile = profile.copy(heightCm = (profile.heightCm + 1).coerceAtMost(220)) })
    Stepper("Gewicht", "%.1f kg".format(profile.weightKg), p.ink, { profile = profile.copy(weightKg = (profile.weightKg - .5).coerceAtLeast(35.0)) }, { profile = profile.copy(weightKg = (profile.weightKg + .5).coerceAtMost(250.0)) })

    SubHead("Ziele")
    Stepper("Schritte", goals.steps.grouped(), p.move, { goals = goals.copy(steps = (goals.steps - 500).coerceAtLeast(1000)) }, { goals = goals.copy(steps = (goals.steps + 500).coerceAtMost(40_000)) })
    Stepper("Bewegen", "${goals.moveKcal} kcal", p.awake, { goals = goals.copy(moveKcal = (goals.moveKcal - 50).coerceAtLeast(100)) }, { goals = goals.copy(moveKcal = (goals.moveKcal + 50).coerceAtMost(2000)) })
    Stepper("Trainieren", "${goals.exerciseMin} min", p.heart, { goals = goals.copy(exerciseMin = (goals.exerciseMin - 5).coerceAtLeast(5)) }, { goals = goals.copy(exerciseMin = (goals.exerciseMin + 5).coerceAtMost(180)) })
    Stepper("Schlaf", "${dur(profile.sleepGoalMin.toLong())} h", p.sleep, { profile = profile.copy(sleepGoalMin = (profile.sleepGoalMin - 15).coerceAtLeast(300)) }, { profile = profile.copy(sleepGoalMin = (profile.sleepGoalMin + 15).coerceAtMost(660)) })

    SubHead("Hinweise")
    Toggle("Ungewohnte Nachtwerte", "Wenn zwei oder mehr Werte ausserhalb deines Bereichs liegen", profile.vitalsAlerts) {
        profile = profile.copy(vitalsAlerts = it); if (it) askNotif()
    }
    Toggle("Schlafenszeit", "30 min vor der empfohlenen Zeit", profile.bedtimeReminder) {
        profile = profile.copy(bedtimeReminder = it); if (it) askNotif()
    }

    if (s.sources.isNotEmpty()) {
        SubHead("Datenquellen")
        s.sources.forEach { src ->
            val on = src.pkg in pick
            Row(Modifier.fillMaxWidth().clickable { pick = if (on) pick - src.pkg else pick + src.pkg }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { pick = if (on) pick - src.pkg else pick + src.pkg },
                    colors = CheckboxDefaults.colors(checkedColor = p.move, uncheckedColor = p.muted, checkmarkColor = p.bg))
                Column {
                    Text(src.label + if (SourceStore.isBand(src)) " (dein Band)" else "", style = Type.body, color = p.ink)
                    Text("seit ${src.firstDay.format(DateTimeFormatter.ofPattern("d. MMMM", DE))}, an ${src.days} ${if (src.days == 1) "Tag" else "Tagen"}",
                        style = Type.small, color = p.muted)
                }
            }
        }
    }

    Action("Speichern") {
        ProfileStore.save(ctx, profile.copy(confirmed = true))
        GoalStore.save(ctx, goals)
        if (pick.isNotEmpty()) SourceStore.save(ctx, pick)
        onClose(); refresh()
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = p.ink)
            Text(sub, style = Type.small, color = p.muted)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.ink, checkedThumbColor = p.bg, uncheckedTrackColor = p.track, uncheckedThumbColor = p.muted, uncheckedBorderColor = p.track))
    }
}
