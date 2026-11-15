package ch.widmer.airview.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.*
import kotlin.math.max
import kotlin.reflect.KClass

val PERMISSIONS: Set<String> = listOf(
    StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
    TotalCaloriesBurnedRecord::class, FloorsClimbedRecord::class, HeartRateRecord::class,
    RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
    OxygenSaturationRecord::class, RespiratoryRateRecord::class, SleepSessionRecord::class,
    ExerciseSessionRecord::class, Vo2MaxRecord::class, WeightRecord::class,
).map { HealthPermission.getReadPermission(it) }.toSet()

data class DayValue(val date: LocalDate, val value: Double)
data class HrPoint(val time: Instant, val bpm: Long)
data class SleepStage(val start: Instant, val end: Instant, val stage: Int)
data class SleepNight(
    val date: LocalDate, val start: Instant, val end: Instant, val stages: List<SleepStage>,
) {
    val asleepMinutes: Long
        get() = if (stages.isEmpty()) Duration.between(start, end).toMinutes()
        else stages.filter { it.stage !in AWAKE_STAGES }
            .sumOf { Duration.between(it.start, it.end).toMinutes() }

    fun minutesIn(vararg types: Int) = stages.filter { it.stage in types }
        .sumOf { Duration.between(it.start, it.end).toMinutes() }

    companion object {
        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
    }
}

data class Workout(
    val title: String, val type: Int, val start: Instant, val end: Instant,
    val calories: Double?, val distanceM: Double?, val avgHr: Long?,
    val zoneMin: IntArray = IntArray(5),
)

/** Ein Tag der drei Ringe: Schritte, aktive kcal, Trainingsminuten. */
data class RingDay(val date: LocalDate, val steps: Long, val moveKcal: Double, val exerciseMin: Int, val estimated: Boolean = false)

data class Snapshot(
    val profile: Profile = Profile(),
    /** Tageszusammenfassungen, älteste zuerst, heute zuletzt. */
    val days: List<DaySummary> = emptyList(),
    val hours: TodayHours = TodayHours(),
    val floors: Double = 0.0,
    val hrToday: List<HrPoint> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val sleep: List<SleepNight> = emptyList(),
    val restingHr: List<DayValue> = emptyList(),
    val hrv: List<DayValue> = emptyList(),
    val spo2: List<DayValue> = emptyList(),
    val respRate: List<DayValue> = emptyList(),
    val vo2max: Double? = null,
    val weightKg: Double? = null,
    val missing: Set<String> = emptySet(),
    val sources: List<Source> = emptyList(),
    val activeSources: Set<String> = emptySet(),   // leer = alle
    /** Erholung pro Morgen (Datum = Tag nach der Nacht). */
    val recoveries: Map<LocalDate, Recovery> = emptyMap(),
    val sleepScores: Map<LocalDate, SleepScore> = emptyMap(),
    val restHrForZones: Double = 60.0,
    /** Verlauf/Hintergrund wären verfügbar, sind aber nicht erlaubt. */
    val extrasMissing: Boolean = false,
) {
    val today: DaySummary get() = days.lastOrNull() ?: DaySummary(LocalDate.now())
    val steps get() = today.steps
    val distanceM get() = today.distanceM
    val activeKcal get() = today.activeKcal
    val totalKcal get() = today.totalKcal
    /** Immer genau n Tage bis heute; fehlende Tage leer – so behalten Wochenansichten ihre Form. */
    fun lastDays(n: Int): List<DaySummary> {
        val by = days.associateBy { it.date }
        val end = LocalDate.now()
        return (n - 1 downTo 0).map { end.minusDays(it.toLong()).let { d -> by[d] ?: DaySummary(d) } }
    }
    val days7 get() = lastDays(7)
    val stepsWeek get() = days7.map { DayValue(it.date, it.steps.toDouble()) }
    val kcalWeek get() = days7.map { DayValue(it.date, it.totalKcal) }
    val rings get() = days7.map { RingDay(it.date, it.steps, it.activeKcal, it.trainingMin, it.activeEstimated) }
    val recoveryToday: Recovery? get() = recoveries[LocalDate.now()]
    val zoneBounds get() = zoneBounds(restHrForZones, profile.maxHr)
}

/** Lesbare Namen für Quellen, die Android ohne Zusatzrechte nicht beschriftet. */
fun friendlyName(pkg: String): String? = when {
    pkg.startsWith("com.fitbit") -> "Google Health"
    pkg.startsWith("com.android.healthconnect.phone") || pkg == "android" -> "Handy"
    pkg == "com.google.android.apps.fitness" -> "Google Fit"
    pkg == "com.sec.android.app.shealth" -> "Samsung Health"
    else -> null
}

data class Source(val pkg: String, val label: String, val firstDay: LocalDate, val days: Int)

/** Gewählte Datenquellen (leer = alle). */
object SourceStore {
    private fun prefs(c: Context) = c.getSharedPreferences("sources", Context.MODE_PRIVATE)
    /** null = noch nie gewählt (dann automatisch nur das Band). */
    fun load(c: Context): Set<String>? = prefs(c).getStringSet("pkgs", null)
    fun save(c: Context, pkgs: Set<String>) = prefs(c).edit().putStringSet("pkgs", pkgs).apply()

    /** Erkennt die App, über die das Fitbit Air seine Daten schreibt. */
    fun isBand(s: Source) = listOf("fitbit", "google health").any {
        s.pkg.lowercase().contains(it) || s.label.lowercase().contains(it)
    }
}

class HealthRepo(private val context: Context) {
    private var origins: Set<DataOrigin> = emptySet()

    /** Welche Apps haben in den letzten 30 Tagen Schritte, Puls, Schlaf oder Kalorien geschrieben? */
    suspend fun sources(): List<Source> {
        val now = Instant.now()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)
        val seen = HashMap<String, MutableSet<LocalDate>>()
        suspend fun <T : Record> scan(k: KClass<T>, time: (T) -> Instant) = runCatching {
            var token: String? = null
            do {
                val r = client.readRecords(ReadRecordsRequest(k, range, pageToken = token))
                r.records.forEach { seen.getOrPut(it.metadata.dataOrigin.packageName) { HashSet() } += time(it).atZone(zone).toLocalDate() }
                token = r.pageToken
            } while (!token.isNullOrEmpty())
        }
        scan(StepsRecord::class) { it.startTime }
        scan(SleepSessionRecord::class) { it.endTime }
        scan(ActiveCaloriesBurnedRecord::class) { it.startTime }
        val pm = context.packageManager
        return seen.map { (pkg, days) ->
            val label = friendlyName(pkg) ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
            Source(pkg, label, days.min(), days.size)
        }.sortedByDescending { it.days }
    }

    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    /** Zusatzrechte nur anfragen, wenn das Health-Connect-Modul sie kennt. */
    fun extraPermissions(): Set<String> = buildSet {
        val f = client.features
        if (f.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        if (f.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    suspend fun load(): Snapshot {
        val profile = ProfileStore.load(context)
        val sources = runCatching { sources() }.getOrDefault(emptyList())
        val chosen = SourceStore.load(context)
            ?: sources.filter(SourceStore::isBand).map { it.pkg }.toSet()   // Standard: nur das Band
        origins = chosen.map { DataOrigin(it) }.toSet()
        val granted = granted()
        fun has(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in granted
        val missing = PERMISSIONS - granted
        val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted

        val today = LocalDate.now(zone)
        val now = Instant.now()
        val span = if (history) 62L else 35L
        val spanStart = today.minusDays(span - 1).atStartOfDay(zone).toInstant()
        val spanRange = TimeRangeFilter.between(spanStart, now)

        var s = Snapshot(profile = profile, missing = missing.map { it.substringAfterLast('.') }.toSet(), sources = sources, activeSources = chosen,
            extrasMissing = runCatching { (extraPermissions() - granted).isNotEmpty() }.getOrDefault(false))

        // --- Nachtwerte (Grundlage für Zonen, Normalbereiche und Erholung)
        if (has(RestingHeartRateRecord::class)) runCatching {
            s = s.copy(restingHr = daily(readAll(RestingHeartRateRecord::class, spanRange)) { it.time to it.beatsPerMinute.toDouble() })
        }
        if (has(HeartRateVariabilityRmssdRecord::class)) runCatching {
            s = s.copy(hrv = daily(readAll(HeartRateVariabilityRmssdRecord::class, spanRange)) { it.time to it.heartRateVariabilityMillis })
        }
        if (has(OxygenSaturationRecord::class)) runCatching {
            s = s.copy(spo2 = daily(readAll(OxygenSaturationRecord::class, spanRange)) { it.time to it.percentage.value })
        }
        if (has(RespiratoryRateRecord::class)) runCatching {
            s = s.copy(respRate = daily(readAll(RespiratoryRateRecord::class, spanRange)) { it.time to it.rate })
        }
        if (has(SleepSessionRecord::class)) runCatching {
            val recs = readAll(SleepSessionRecord::class, TimeRangeFilter.between(spanStart.minus(Duration.ofDays(1)), now))
            s = s.copy(sleep = recs.map { r ->
                SleepNight(r.endTime.atZone(zone).toLocalDate(), r.startTime, r.endTime, r.stages.map { SleepStage(it.startTime, it.endTime, it.stage) })
            }.groupBy { it.date }.map { (_, n) -> n.maxBy { it.asleepMinutes } }.sortedBy { it.date })
        }
        val restHr = s.restingHr.takeLast(14).map { it.value }.let { if (it.isEmpty()) 60.0 else median(it.map { v -> v.toInt() }) }
        s = s.copy(restHrForZones = restHr)
        val bounds = zoneBounds(restHr, profile.maxHr)

        // --- Schlafwerte und Erholung pro Tag
        val scores = HashMap<LocalDate, SleepScore>()
        s.sleep.forEachIndexed { i, n -> scores[n.date] = sleepScore(n, s.sleep.take(i), profile.sleepGoalMin, zone) }
        val rec = HashMap<LocalDate, Recovery>()
        val hrvBy = s.hrv.associateBy { it.date }; val rhrBy = s.restingHr.associateBy { it.date }; val respBy = s.respRate.associateBy { it.date }
        for (d in (s.hrv.map { it.date } + s.restingHr.map { it.date }).toSortedSet()) {
            fun prior(v: List<DayValue>) = v.filter { it.date < d && it.date >= d.minusDays(30) }.map { it.value }
            recovery(hrvBy[d]?.value, prior(s.hrv), rhrBy[d]?.value, prior(s.restingHr), respBy[d]?.value, prior(s.respRate), scores[d])
                ?.let { rec[d] = it }
        }
        s = s.copy(sleepScores = scores, recoveries = rec)

        // --- Trainings (für Tagesminuten und Liste)
        val sessions = if (has(ExerciseSessionRecord::class)) runCatching { readAll(ExerciseSessionRecord::class, spanRange) }.getOrDefault(emptyList()) else emptyList()

        // --- Tageszusammenfassungen: abgeschlossene Tage aus dem Cache, Rest neu
        val key = chosen.sorted().joinToString(",") + "|" + profile.cacheKey + "|v1"
        val cache = DayCache(context)
        val cached = cache.load(key).toMutableMap()
        val days = ArrayList<DaySummary>()
        var hours = TodayHours()
        var hrToday: List<HrPoint> = emptyList()
        for (back in span - 1 downTo 0) {
            val d = today.minusDays(back)
            val closed = back >= 2
            val c = cached[d]
            if (closed && c != null) { days += c; continue }
            val r = runCatching { daySummary(d, profile, bounds, sessions, granted, wantHours = back == 0L) }.getOrNull() ?: continue
            days += r.first
            if (closed) cached[d] = r.first
            if (back == 0L) { hours = r.second; hrToday = r.third }
        }
        cache.save(key, cached.values.filter { it.date >= today.minusDays(70) })
        // erst ab dem ersten Tag mit Daten zeigen (keine leeren Wochen vor dem Kauf), aber mindestens 7 Tage
        val first = days.indexOfFirst { it.steps > 0 || it.hrMinutes > 0 }
        val shown = if (first < 0) days.takeLast(7) else days.drop(first).let { if (it.size < 7) days.takeLast(7) else it }
        s = s.copy(days = shown, hours = hours, hrToday = hrToday)

        if (has(FloorsClimbedRecord::class)) runCatching {
            s = s.copy(floors = client.aggregate(AggregateRequest(setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
                TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), now), origins))[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: 0.0)
        }

        // --- Trainingsliste (30 Tage) mit Zonen
        val monthAgo = today.minusDays(29).atStartOfDay(zone).toInstant()
        val list = sessions.filter { it.startTime >= monthAgo }.sortedByDescending { it.startTime }.take(12).map { e ->
            val range = TimeRangeFilter.between(e.startTime, e.endTime)
            val dist = if (has(DistanceRecord::class)) runCatching {
                client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range, origins))[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            }.getOrNull() else null
            val pts = if (has(HeartRateRecord::class)) runCatching {
                readAll(HeartRateRecord::class, range).flatMap { r -> r.samples.map { HrPoint(it.time, it.beatsPerMinute) } }
            }.getOrDefault(emptyList()) else emptyList()
            val zm = zoneMinutes(minuteBins(pts, e.startTime), bounds)
            Workout(e.title ?: exerciseName(e.exerciseType), e.exerciseType, e.startTime, e.endTime, null, dist,
                pts.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toLong(), zm)
        }
        s = s.copy(workouts = list)

        if (has(Vo2MaxRecord::class)) runCatching {
            s = s.copy(vo2max = readAll(Vo2MaxRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(90)), now))
                .maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram)
        }
        if (has(WeightRecord::class)) runCatching {
            s = s.copy(weightKg = readAll(WeightRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(365)), now))
                .maxByOrNull { it.time }?.weight?.inKilograms)
        }
        WidgetState.save(context, s.today, GoalStore.load(context))
        return s
    }

    /**
     * Ein Tag: Summen, aktive kcal (gemessen oder geschätzt), Trainingsminuten, Pulszonen.
     * Für heute zusätzlich Werte pro Stunde und die Pulspunkte.
     */
    private suspend fun daySummary(
        d: LocalDate, profile: Profile, bounds: DoubleArray, sessions: List<ExerciseSessionRecord>,
        granted: Set<String>, wantHours: Boolean,
    ): Triple<DaySummary, TodayHours, List<HrPoint>> {
        fun has(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in granted
        val from = d.atStartOfDay(zone).toInstant()
        val to = minOf(d.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val range = TimeRangeFilter.between(from, to)

        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        val a = if (metrics.isEmpty()) null else client.aggregate(AggregateRequest(metrics, range, origins))
        val steps = a?.get(StepsRecord.COUNT_TOTAL) ?: 0L
        val dist = a?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: 0.0
        val total = a?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0
        val measuredActive = a?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories ?: 0.0

        // aktive kcal pro Stunde: gemessen, sonst Gesamt minus anteiliger Grundumsatz (nie negativ)
        val hourKcal = DoubleArray(24)
        val estimated = measuredActive < 1 && total > 0
        if (estimated && has(TotalCaloriesBurnedRecord::class)) {
            val bmrPerMin = profile.bmrPerDay / 1440
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range, Duration.ofHours(1), origins))
                .forEach { b ->
                    val h = b.startTime.atZone(zone).hour
                    val mins = Duration.between(b.startTime, b.endTime).toMinutes()
                    val kcal = b.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                    hourKcal[h] += max(0.0, kcal - bmrPerMin * mins)
                }
        } else if (wantHours && has(ActiveCaloriesBurnedRecord::class)) {
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range, Duration.ofHours(1), origins))
                .forEach { b -> hourKcal[b.startTime.atZone(zone).hour] += b.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0 }
        }
        val active = if (estimated) hourKcal.sum() else measuredActive

        // Trainingsminuten: zügig gehen (≥ 100 Schritte/min), Trainings, Puls ab Zone 2
        val training = HashSet<Int>()
        val stepsHour = IntArray(24)
        if (has(StepsRecord::class)) {
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofMinutes(1), origins))
                .forEach { b ->
                    val n = b.result[StepsRecord.COUNT_TOTAL] ?: 0
                    val m = Duration.between(from, b.startTime).toMinutes().toInt()
                    if (m in 0 until 1440) { stepsHour[m / 60] += n.toInt(); if (n >= 100) training += m }
                }
        }
        sessions.filter { it.endTime > from && it.startTime < to }.forEach { e ->
            var m = Duration.between(from, maxOf(e.startTime, from)).toMinutes().toInt()
            val end = Duration.between(from, minOf(e.endTime, to)).toMinutes().toInt()
            while (m < end) training += m++
        }
        val pts = if (has(HeartRateRecord::class)) readAll(HeartRateRecord::class, range)
            .flatMap { r -> r.samples.map { HrPoint(it.time, it.beatsPerMinute) } }.sortedBy { it.time } else emptyList()
        val bins = minuteBins(pts, from)
        bins.forEach { (m, bpm) -> if (zoneOf(bpm, bounds) >= 2) training += m }
        val zm = zoneMinutes(bins, bounds)

        val trainHour = IntArray(24).also { arr -> training.forEach { if (it in 0 until 1440) arr[it / 60]++ } }
        val sum = DaySummary(d, steps, dist, total, active, estimated, training.size, zm, trimp(zm), bins.size)
        return Triple(sum, TodayHours(stepsHour, hourKcal, trainHour), if (wantHours) pts else emptyList())
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, range, dataOriginFilter = origins, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    private fun <T> daily(recs: List<T>, f: (T) -> Pair<Instant, Double>): List<DayValue> =
        recs.map(f).groupBy { it.first.atZone(zone).toLocalDate() }
            .map { (d, v) -> DayValue(d, v.map { it.second }.average()) }
            .sortedBy { it.date }
}

fun exerciseName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Gehen"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Laufen"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Laufband"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Velo"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Hometrainer"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Wandern"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Schwimmen"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Schwimmen (offen)"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Krafttraining"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Gewichtheben"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Crosstrainer"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rudergerät"
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Ski"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Fussball"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Tanzen"
    else -> "Training"
}
