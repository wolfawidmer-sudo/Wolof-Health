package ch.widmer.airview.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Eigene Auswertungen. Alle Werte sind Näherungen nach öffentlich beschriebenen Prinzipien
 * (Karvonen-Zonen, Edwards-TRIMP, Apple-Schlafwert-Aufteilung, WHOOP-Zonen für die Erholung).
 * Es sind nicht die Algorithmen von Google, Apple oder WHOOP.
 */

// ---------- Pulszonen ----------

/** Untere Grenzen der Zonen 1–5 in bpm: 50/60/70/80/90 % der Herzfrequenzreserve (Karvonen). */
fun zoneBounds(restHr: Double, maxHr: Double): DoubleArray {
    val reserve = (maxHr - restHr).coerceAtLeast(40.0)
    return doubleArrayOf(.5, .6, .7, .8, .9).map { restHr + it * reserve }.toDoubleArray()
}

/** Zone 0 (unter Zone 1) bis 5. */
fun zoneOf(bpm: Double, bounds: DoubleArray): Int = bounds.indexOfLast { bpm >= it } + 1

/** Mittelwert pro Minute ab Tagesbeginn. */
fun minuteBins(points: List<HrPoint>, dayStart: Instant): Map<Int, Double> =
    points.groupBy { (Duration.between(dayStart, it.time).toMinutes()).toInt() }
        .filterKeys { it in 0 until 1440 }
        .mapValues { (_, v) -> v.map { it.bpm }.average() }

/** Minuten pro Zone 1–5. Minuten ohne Messwert zählen nicht. */
fun zoneMinutes(bins: Map<Int, Double>, bounds: DoubleArray): IntArray {
    val out = IntArray(5)
    bins.values.forEach { val z = zoneOf(it, bounds); if (z > 0) out[z - 1]++ }
    return out
}

/** Edwards-TRIMP: Minuten × Zonennummer. */
fun trimp(zm: IntArray): Double = zm.withIndex().sumOf { (i, m) -> m * (i + 1.0) }

// ---------- Statistik ----------

fun mean(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.average()
fun sd(v: List<Double>): Double {
    if (v.size < 2) return 0.0
    val m = v.average()
    return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
}

/** Persönlicher Normalbereich: Mittel ± 2 SD der vorherigen Nächte (mind. 7). */
data class NormalRange(val mean: Double, val low: Double, val high: Double, val n: Int) {
    fun contains(x: Double) = x in low..high
}

const val MIN_BASELINE = 7

fun normalRange(prior: List<Double>, sdFloor: Double): NormalRange? {
    if (prior.size < MIN_BASELINE) return null
    val m = mean(prior)
    val s = max(sd(prior), sdFloor)
    return NormalRange(m, m - 2 * s, m + 2 * s, prior.size)
}

/** Mindest-Streuung je Messgrösse, damit wenige ruhige Nächte keinen zu engen Bereich ergeben. */
object Floors {
    fun hrv(mean: Double) = max(3.0, mean * 0.08)
    const val RHR = 1.5
    const val RESP = 0.4
    const val SPO2 = 0.5
}

// ---------- Schlafwert ----------

data class SleepScore(
    val total: Int,
    val duration: Int, val durationMax: Int,
    val rhythm: Int?, val rhythmMax: Int,
    val calm: Int, val calmMax: Int,
    val awakeMin: Long, val wakeups: Int, val bedDeviationMin: Int?,
) {
    val label: String get() = when {
        total >= 96 -> "Sehr hoch"
        total >= 81 -> "Hoch"
        total >= 61 -> "Gut"
        total >= 41 -> "Tief"
        else -> "Sehr tief"
    }
}

/** Minuten seit 18 Uhr – damit liegen Zubettgeh-Zeiten um Mitternacht nicht an einer Bruchstelle. */
fun bedMinute(i: Instant, zone: ZoneId): Int {
    val t = i.atZone(zone).toLocalTime()
    return ((t.hour * 60 + t.minute) - 18 * 60 + 1440) % 1440
}

fun median(v: List<Int>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }

/**
 * Aufteilung wie bei Apple: Dauer 50, Regelmässigkeit 30, Ruhe 20 Punkte.
 * Ohne mindestens 2 frühere Nächte wird die Regelmässigkeit weggelassen und hochgerechnet.
 */
fun sleepScore(n: SleepNight, prior: List<SleepNight>, goalMin: Int, zone: ZoneId): SleepScore {
    val dur = (50 * min(1.0, n.asleepMinutes / goalMin.toDouble())).toInt()
    val awake = n.minutesIn(*SleepNight.AWAKE_STAGES.toIntArray())
    val sorted = n.stages.sortedBy { it.start }
    // Aufwachphasen mitten in der Nacht (nicht die erste und letzte Phase)
    val wakeups = sorted.drop(1).dropLast(1).count { it.stage in SleepNight.AWAKE_STAGES }
    val calm = (20 * (1 - min(1.0, awake / 60.0 * .6 + wakeups / 10.0 * .4))).toInt().coerceIn(0, 20)
    val recent = prior.takeLast(7)
    val (rhythm, dev) = if (recent.size >= 2) {
        val d = abs(bedMinute(n.start, zone) - median(recent.map { bedMinute(it.start, zone) })).toInt()
        (30 * (1 - min(1.0, d / 90.0))).toInt() to d
    } else null to null
    val total = if (rhythm != null) dur + rhythm + calm else ((dur + calm) * 100.0 / 70).toInt()
    return SleepScore(total.coerceIn(0, 100), dur, 50, rhythm, 30, calm, 20, awake, wakeups, dev)
}

// ---------- Erholung ----------

data class Part(val label: String, val value: Double, val baseline: Double, val unit: String, val effect: Double)

data class Recovery(val score: Int, val parts: List<Part>) {
    val label: String get() = when {
        score >= 67 -> "Bereit"
        score >= 34 -> "Normal"
        else -> "Schonen"
    }
}

private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

/**
 * Erholung 0–100 aus der Abweichung zur eigenen Baseline (z-Werte):
 * HRV höher = besser, Ruhepuls tiefer = besser, Atemfrequenz zählt nur, wenn sie steigt,
 * dazu der Schlafwert. Abbildung so, dass ein durchschnittlicher Tag bei ~65 landet.
 * Nur wenn HRV und Ruhepuls für die Nacht vorliegen und je mind. 7 frühere Nächte da sind.
 */
fun recovery(
    hrv: Double?, hrvPrior: List<Double>,
    rhr: Double?, rhrPrior: List<Double>,
    resp: Double?, respPrior: List<Double>,
    sleep: SleepScore?,
): Recovery? {
    if (hrv == null || rhr == null || hrvPrior.size < MIN_BASELINE || rhrPrior.size < MIN_BASELINE) return null
    val hm = mean(hrvPrior); val hs = max(sd(hrvPrior), Floors.hrv(hm))
    val rm = mean(rhrPrior); val rs = max(sd(rhrPrior), Floors.RHR)
    val zH = ((hrv - hm) / hs).coerceIn(-3.0, 3.0)
    val zR = ((rhr - rm) / rs).coerceIn(-3.0, 3.0)
    val parts = mutableListOf(
        Part("HRV", hrv, hm, "ms", .5 * zH),
        Part("Ruhepuls", rhr, rm, "bpm", -.3 * zR),
    )
    if (resp != null && respPrior.size >= MIN_BASELINE) {
        val am = mean(respPrior); val asd = max(sd(respPrior), Floors.RESP)
        parts += Part("Atemfrequenz", resp, am, "/min", -.2 * max(0.0, (resp - am) / asd).coerceAtMost(3.0))
    }
    if (sleep != null) parts += Part("Schlafwert", sleep.total.toDouble(), 75.0, "", .2 * ((sleep.total - 75) / 15.0).coerceIn(-3.0, 2.0))
    val combined = parts.sumOf { it.effect }
    val score = (100 * sigmoid(.6 + 1.15 * combined)).toInt().coerceIn(1, 99)
    return Recovery(score, parts)
}

// ---------- Belastung ----------

data class Load(val acute: Double, val chronic: Double, val ratio: Double) {
    val label: String get() = when {
        ratio < .6 -> "Weit unter deinem Schnitt"
        ratio < .85 -> "Unter deinem Schnitt"
        ratio <= 1.15 -> "Im Gleichgewicht"
        ratio <= 1.4 -> "Über deinem Schnitt"
        else -> "Weit über deinem Schnitt"
    }
    val short: String get() = when {
        ratio < .6 -> "weit unter"
        ratio < .85 -> "unter"
        ratio <= 1.15 -> "ausgeglichen"
        ratio <= 1.4 -> "über"
        else -> "weit über"
    }
}

/** 7 Tage gegen 28 Tage (Tagesmittel). Erst wenn mindestens 28 Tage mit Pulsdaten vorliegen. */
fun load(days: List<DaySummary>): Load? {
    val withHr = days.filter { it.hrMinutes > 60 }
    if (withHr.size < 28) return null
    val last28 = days.takeLast(28)
    val acute = days.takeLast(7).map { it.trimp }.average()
    val chronic = last28.map { it.trimp }.average().coerceAtLeast(1.0)
    return Load(acute, chronic, acute / chronic)
}

/** Zielband pro Tag aus dem bisherigen Tagesmittel (ab 7 Tagen mit Pulsdaten). */
fun loadBand(days: List<DaySummary>): ClosedFloatingPointRange<Double>? {
    val past = days.dropLast(1).filter { it.hrMinutes > 60 }.takeLast(28)
    if (past.size < 7) return null
    val m = past.map { it.trimp }.average()
    return (m * .8)..(m * 1.25)
}

// ---------- Schlafbedarf ----------

data class SleepNeed(val needMin: Int, val debtMin: Int, val strainAdjMin: Int)

/**
 * Eigene einfache Regel (WHOOP veröffentlicht keine Formel):
 * Bedarf = Ziel + Anpassung für die Belastung von heute + die Hälfte der Schlafschuld.
 * Schlafschuld = fehlende Minuten der letzten 3 Nächte gegenüber dem Ziel (nie negativ).
 */
fun sleepNeed(goalMin: Int, nights: List<SleepNight>, days: List<DaySummary>): SleepNeed {
    val debt = max(0L, nights.takeLast(3).sumOf { goalMin - it.asleepMinutes }).toInt()
    val past = days.dropLast(1).filter { it.hrMinutes > 60 }.takeLast(28)
    val today = days.lastOrNull()?.trimp ?: 0.0
    val adj = if (past.size >= 7) {
        val m = past.map { it.trimp }.average().coerceAtLeast(1.0)
        ((today - m) / m * 30).toInt().coerceIn(-15, 45)
    } else 0
    return SleepNeed(goalMin + adj + min(debt, 120) / 2, debt, adj)
}

// ---------- Trends ----------

data class Trend(val now: Double, val before: Double) {
    val deltaPct: Double get() = if (before == 0.0) 0.0 else (now - before) / before * 100
}

/** Letzte 30 Tage gegen die 30 davor; je mindestens 10 Werte. */
fun trend30(values: List<DayValue>, today: LocalDate): Trend? {
    val a = values.filter { it.date > today.minusDays(30) }.map { it.value }
    val b = values.filter { it.date <= today.minusDays(30) && it.date > today.minusDays(60) }.map { it.value }
    if (a.size < 10 || b.size < 10) return null
    return Trend(a.average(), b.average())
}
