package ch.widmer.airview.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Eine Nachtmetrik mit ihrem persönlichen Normalbereich. */
data class Vital(
    val key: String, val label: String, val unit: String, val fmt: String,
    val values: List<DayValue>, val range: NormalRange?,
) {
    val last: DayValue? get() = values.lastOrNull()
    /** null = noch kein Bereich; sonst ob der letzte Wert drin liegt. */
    val inRange: Boolean? get() = last?.let { l -> range?.contains(l.value) }
}

/** Bereich aus den bis zu 30 Nächten vor dem letzten Wert. */
fun vital(key: String, label: String, unit: String, fmt: String, values: List<DayValue>, floor: (Double) -> Double): Vital {
    val last = values.lastOrNull()
    val prior = if (last == null) emptyList() else values.filter { it.date < last.date && it.date >= last.date.minusDays(30) }.map { it.value }
    return Vital(key, label, unit, fmt, values, normalRange(prior, floor(mean(prior).takeIf { !it.isNaN() } ?: 0.0)))
}

fun vitals(s: Snapshot): List<Vital> = listOfNotNull(
    vital("rhr", "Ruhepuls", "bpm", "%.0f", s.restingHr) { Floors.RHR }.takeIf { s.restingHr.isNotEmpty() },
    vital("hrv", "HRV", "ms", "%.0f", s.hrv) { Floors.hrv(it) }.takeIf { s.hrv.isNotEmpty() },
    vital("resp", "Atemfrequenz", "/min", "%.1f", s.respRate) { Floors.RESP }.takeIf { s.respRate.isNotEmpty() },
    vital("spo2", "Sauerstoff", "%", "%.1f", s.spo2) { Floors.SPO2 }.takeIf { s.spo2.isNotEmpty() },
)

/** Metriken der letzten Nacht ausserhalb des Bereichs (nur wenn die Nacht von heute ist). */
fun outOfRangeToday(s: Snapshot, today: LocalDate = LocalDate.now()): List<Vital> =
    vitals(s).filter { it.last?.date == today && it.inRange == false }

/**
 * Empfohlene Zubettgeh-Zeit: übliche Aufwachzeit (Median der letzten 7 Nächte)
 * minus Schlafbedarf minus 15 min zum Einschlafen. null ohne genug Nächte.
 */
fun bedtimeFor(s: Snapshot, zone: ZoneId = ZoneId.systemDefault()): LocalTime? {
    val nights = s.sleep.takeLast(7)
    if (nights.size < 3) return null
    val wake = median(nights.map { val t = it.end.atZone(zone).toLocalTime(); t.hour * 60 + t.minute }).toLong()
    val need = sleepNeed(s.profile.sleepGoalMin, s.sleep, s.days).needMin
    return LocalTime.MIDNIGHT.plusMinutes(wake).minus(Duration.ofMinutes(need + 15L))
}
