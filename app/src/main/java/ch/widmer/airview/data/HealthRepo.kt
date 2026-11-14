package ch.widmer.airview.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.*
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
)

/** Ein Tag der drei Ringe: Schritte, aktive kcal, zügige Minuten. */
data class RingDay(val date: LocalDate, val steps: Long, val moveKcal: Double, val exerciseMin: Int)

data class Snapshot(
    val steps: Long = 0, val distanceM: Double = 0.0, val activeKcal: Double = 0.0,
    val totalKcal: Double = 0.0, val floors: Double = 0.0, val activeMinutes: Long = 0,
    val hrToday: List<HrPoint> = emptyList(),
    val stepsWeek: List<DayValue> = emptyList(),
    val kcalWeek: List<DayValue> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val sleep: List<SleepNight> = emptyList(),
    val restingHr: List<DayValue> = emptyList(),
    val hrv: List<DayValue> = emptyList(),
    val spo2: List<DayValue> = emptyList(),
    val respRate: List<DayValue> = emptyList(),
    val vo2max: Double? = null,
    val weightKg: Double? = null,
    val missing: Set<String> = emptySet(),
    val rings: List<RingDay> = emptyList(),
    val sources: List<Source> = emptyList(),
    val activeSources: Set<String> = emptySet(),   // leer = alle
)

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
        scan(HeartRateRecord::class) { it.startTime }
        scan(SleepSessionRecord::class) { it.endTime }
        scan(ActiveCaloriesBurnedRecord::class) { it.startTime }
        val pm = context.packageManager
        return seen.map { (pkg, days) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Source(pkg, label, days.min(), days.size)
        }.sortedByDescending { it.days }
    }

    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun load(): Snapshot {
        val sources = runCatching { sources() }.getOrDefault(emptyList())
        val chosen = SourceStore.load(context)
            ?: sources.filter(SourceStore::isBand).map { it.pkg }.toSet()   // Standard: nur das Band
        origins = chosen.map { DataOrigin(it) }.toSet()
        val granted = granted()
        fun has(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in granted
        val missing = PERMISSIONS - granted

        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val todayRange = TimeRangeFilter.between(dayStart, now)
        val weekStartLocal = today.minusDays(6).atStartOfDay()
        val monthStart = today.minusDays(29).atStartOfDay(zone).toInstant()
        val monthRange = TimeRangeFilter.between(monthStart, now)

        var s = Snapshot(missing = missing.map { it.substringAfterLast('.') }.toSet(), sources = sources, activeSources = chosen)

        // Heute: Summen
        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(FloorsClimbedRecord::class)) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
        }
        if (metrics.isNotEmpty()) runCatching {
            val a = client.aggregate(AggregateRequest(metrics, todayRange, origins))
            s = s.copy(
                steps = a[StepsRecord.COUNT_TOTAL] ?: 0,
                distanceM = a[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
                activeKcal = a[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
                totalKcal = a[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
                floors = a[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: 0.0,
            )
        }

        // 7 Tage Schritte / Kalorien
        val weekMetrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (weekMetrics.isNotEmpty()) runCatching {
            val rows = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    weekMetrics,
                    TimeRangeFilter.between(weekStartLocal, LocalDateTime.now(zone)),
                    Period.ofDays(1),
                    origins,
                )
            )
            val byDate = rows.associateBy { it.startTime.toLocalDate() }
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            s = s.copy(
                stepsWeek = days.map { DayValue(it, (byDate[it]?.result?.get(StepsRecord.COUNT_TOTAL) ?: 0L).toDouble()) },
                kcalWeek = days.map { DayValue(it, byDate[it]?.result?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0) },
            )
        }

        // Herzfrequenz heute
        if (has(HeartRateRecord::class)) runCatching {
            val recs = readAll(HeartRateRecord::class, todayRange)
            s = s.copy(hrToday = recs.flatMap { r -> r.samples.map { HrPoint(it.time, it.beatsPerMinute) } }
                .sortedBy { it.time })
        }

        // Trainings (30 Tage)
        if (has(ExerciseSessionRecord::class)) runCatching {
            val sessions = readAll(ExerciseSessionRecord::class, monthRange).sortedByDescending { it.startTime }
            val list = sessions.map { e ->
                val range = TimeRangeFilter.between(e.startTime, e.endTime)
                val kcal = if (has(ActiveCaloriesBurnedRecord::class)) runCatching {
                    client.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range, origins))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                }.getOrNull() else null
                val dist = if (has(DistanceRecord::class)) runCatching {
                    client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range, origins))[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                }.getOrNull() else null
                val hr = if (has(HeartRateRecord::class)) runCatching {
                    client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), range, origins))[HeartRateRecord.BPM_AVG]
                }.getOrNull() else null
                Workout(e.title ?: exerciseName(e.exerciseType), e.exerciseType, e.startTime, e.endTime, kcal, dist, hr)
            }
            val activeToday = sessions.filter { it.endTime.isAfter(dayStart) }
                .sumOf { Duration.between(maxOf(it.startTime, dayStart), it.endTime).toMinutes() }
            s = s.copy(workouts = list, activeMinutes = activeToday)
        }

        // Schlaf (14 Nächte)
        if (has(SleepSessionRecord::class)) runCatching {
            val from = today.minusDays(14).atStartOfDay(zone).toInstant()
            val recs = readAll(SleepSessionRecord::class, TimeRangeFilter.between(from, now))
            s = s.copy(sleep = recs.map { r ->
                SleepNight(
                    r.endTime.atZone(zone).toLocalDate(), r.startTime, r.endTime,
                    r.stages.map { SleepStage(it.startTime, it.endTime, it.stage) },
                )
            }.groupBy { it.date }.map { (_, n) -> n.maxBy { it.asleepMinutes } }.sortedBy { it.date })
        }

        if (has(RestingHeartRateRecord::class)) runCatching {
            s = s.copy(restingHr = daily(readAll(RestingHeartRateRecord::class, monthRange)) { it.time to it.beatsPerMinute.toDouble() })
        }
        if (has(HeartRateVariabilityRmssdRecord::class)) runCatching {
            s = s.copy(hrv = daily(readAll(HeartRateVariabilityRmssdRecord::class, monthRange)) { it.time to it.heartRateVariabilityMillis })
        }
        if (has(OxygenSaturationRecord::class)) runCatching {
            s = s.copy(spo2 = daily(readAll(OxygenSaturationRecord::class, monthRange)) { it.time to it.percentage.value })
        }
        if (has(RespiratoryRateRecord::class)) runCatching {
            s = s.copy(respRate = daily(readAll(RespiratoryRateRecord::class, monthRange)) { it.time to it.rate })
        }
        if (has(Vo2MaxRecord::class)) runCatching {
            s = s.copy(vo2max = readAll(Vo2MaxRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(90)), now))
                .maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram)
        }
        if (has(WeightRecord::class)) runCatching {
            s = s.copy(weightKg = readAll(WeightRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(365)), now))
                .maxByOrNull { it.time }?.weight?.inKilograms)
        }
        s = s.copy(rings = runCatching { rings(today, has(StepsRecord::class), has(ActiveCaloriesBurnedRecord::class), has(ExerciseSessionRecord::class)) }
            .getOrDefault(emptyList()))
        return s
    }

    /** Ringe für die letzten 7 Tage (heute zuletzt). */
    private suspend fun rings(today: LocalDate, steps: Boolean, kcal: Boolean, exercise: Boolean): List<RingDay> {
        val now = Instant.now()
        return (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val from = d.atStartOfDay(zone).toInstant()
            val to = minOf(d.plusDays(1).atStartOfDay(zone).toInstant(), now)
            val range = TimeRangeFilter.between(from, to)

            val stepCount = if (steps) runCatching {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range, origins))[StepsRecord.COUNT_TOTAL]
            }.getOrNull() ?: 0L else 0L
            val move = if (kcal) runCatching {
                client.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range, origins))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            }.getOrNull() ?: 0.0 else 0.0

            // Minute des Tages -> zügig? (≥ 100 Schritte/min oder in einem Training)
            val brisk = HashSet<Long>()
            if (steps) runCatching {
                client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofMinutes(1), origins))
                    .filter { (it.result[StepsRecord.COUNT_TOTAL] ?: 0) >= 100 }
                    .forEach { brisk += Duration.between(from, it.startTime).toMinutes() }
            }
            if (exercise) runCatching {
                readAll(ExerciseSessionRecord::class, range).forEach { e ->
                    var m = Duration.between(from, maxOf(e.startTime, from)).toMinutes()
                    val end = Duration.between(from, minOf(e.endTime, to)).toMinutes()
                    while (m < end) brisk += m++
                }
            }
            RingDay(d, stepCount, move, brisk.size)
        }
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
