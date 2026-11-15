package ch.widmer.airview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord as S
import ch.widmer.airview.data.*
import ch.widmer.airview.ui.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*
import kotlin.math.sin
import kotlin.random.Random

/** Bildschirmfotos mit Beispieldaten. «voll» = gut gefüllter Monat, «start» = die ersten zwei Tage mit dem Band. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreensShot {
    private val z = ZoneId.systemDefault()
    private val today = LocalDate.now(z)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(z).toInstant()

    private fun night(d: LocalDate, rnd: Random): SleepNight {
        val start = d.minusDays(1).atTime(22, 40).plusMinutes(rnd.nextLong(-40, 70)).atZone(z).toInstant()
        val pattern = listOf(S.STAGE_TYPE_LIGHT, S.STAGE_TYPE_DEEP, S.STAGE_TYPE_LIGHT, S.STAGE_TYPE_REM, S.STAGE_TYPE_AWAKE)
        var t = start; val stages = ArrayList<SleepStage>()
        repeat(22) { i ->
            val st = pattern[i % pattern.size]
            val len = when (st) { S.STAGE_TYPE_AWAKE -> rnd.nextLong(2, 9); S.STAGE_TYPE_DEEP -> rnd.nextLong(10, 35); else -> rnd.nextLong(12, 40) }
            stages += SleepStage(t, t.plusSeconds(len * 60), st); t = t.plusSeconds(len * 60)
        }
        return SleepNight(d, start, t, stages)
    }

    private fun sample(full: Boolean = true): Snapshot {
        val rnd = Random(7)
        val n = if (full) 35 else 2
        val profile = Profile(age = 31, male = true, heightCm = 180, weightKg = 76.0, confirmed = full)
        val days = (n - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val zm = intArrayOf(rnd.nextInt(30, 90), rnd.nextInt(10, 50), rnd.nextInt(0, 30), rnd.nextInt(0, 14), rnd.nextInt(0, 5))
            val steps = rnd.nextLong(4000, 14000)
            DaySummary(d, steps, steps * .75, rnd.nextDouble(2000.0, 2800.0), rnd.nextDouble(200.0, 700.0), true,
                rnd.nextInt(10, 70), zm, trimp(zm), 1200)
        }.toMutableList()
        days[days.lastIndex] = days.last().copy(steps = 7_057, activeKcal = 315.0, trainingMin = 53, distanceM = 4_800.0)
        val sleep = (n - 1 downTo 0).map { night(today.minusDays(it.toLong()), rnd) }
        fun series(base: Double, amp: Double) = (n - 1 downTo 0).map { DayValue(today.minusDays(it.toLong()), base + amp * sin(it * 1.3) + rnd.nextDouble(-amp, amp) * .4) }
        val rhr = series(55.0, 2.0); val hrv = series(72.0, 9.0); val resp = series(15.2, .5)
        val profileGoal = profile.sleepGoalMin
        val scores = sleep.mapIndexed { i, s -> s.date to sleepScore(s, sleep.take(i), profileGoal, z) }.toMap()
        val recs = HashMap<LocalDate, Recovery>()
        rhr.map { it.date }.forEach { d ->
            fun prior(v: List<DayValue>) = v.filter { it.date < d }.map { it.value }
            recovery(hrv.first { it.date == d }.value, prior(hrv), rhr.first { it.date == d }.value, prior(rhr),
                resp.first { it.date == d }.value, prior(resp), scores[d])?.let { recs[d] = it }
        }
        val now = Instant.now()
        val hr = generateSequence(at(today, 0)) { it.plusSeconds(120) }.takeWhile { it < now }.map {
            val h = Duration.between(at(today, 0), it).toMinutes() / 60.0
            val base = if (h < 7.4) 54 + 3 * sin(h * 3) else 72 + 8 * sin(h * 1.3)
            val walk = if (h in 8.0..8.6 || h in 12.2..12.9) 48.0 else 0.0
            HrPoint(it, (base + walk + (it.epochSecond % 7)).toLong())
        }.toList()
        val hours = TodayHours(
            IntArray(24) { h -> if (h in 7..LocalTime.now().hour) rnd.nextInt(80, 1200) else 0 },
            DoubleArray(24) { h -> if (h in 7..LocalTime.now().hour) rnd.nextDouble(5.0, 60.0) else 0.0 },
            IntArray(24) { h -> if (h == 8 || h == 12) rnd.nextInt(15, 40) else if (h in 7..LocalTime.now().hour) rnd.nextInt(0, 5) else 0 },
        )
        val workouts = if (full) listOf(
            Workout("Gehen", 79, at(today, 8, 2), at(today, 8, 38), null, 2_940.0, 104, intArrayOf(10, 18, 6, 0, 0)),
            Workout("Laufen", 56, at(today.minusDays(2), 18, 10), at(today.minusDays(2), 18, 58), null, 8_210.0, 151, intArrayOf(2, 6, 14, 20, 6)),
            Workout("Velo", 8, at(today.minusDays(4), 7, 30), at(today.minusDays(4), 8, 5), null, 11_400.0, 128, intArrayOf(4, 12, 15, 4, 0)),
        ) else emptyList()
        return Snapshot(
            profile = profile, days = days, hours = hours, floors = 9.0, hrToday = hr, workouts = workouts,
            sleep = sleep, restingHr = rhr, hrv = hrv, respRate = resp, vo2max = if (full) 46.0 else null,
            sources = listOf(Source("com.fitbit.FitbitMobile", "Google Health", today.minusDays(n - 1L), n)),
            activeSources = setOf("com.fitbit.FitbitMobile"),
            recoveries = recs, sleepScores = scores, restHrForZones = 55.0,
        )
    }

    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, page: Int, at: Long = 2500, full: Boolean = true, update: UpdateState = UpdateState.Idle) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AirTheme { Pages(sample(full), false, {}, {}, startPage = page, update = update) } }
        rule.mainClock.advanceTimeBy(at)
        rule.onRoot().captureRoboImage("shots/$name.png")
    }

    /** Detailansicht so, wie sie im Sheet erscheint (ohne Animation des Sheets selbst). */
    private fun sheet(name: String, sheet: Sheet, night: Boolean = false, full: Boolean = true) {
        rule.setContent {
            AirTheme {
                val p = if (night) nightPalette() else basePalette()
                CompositionLocalProvider(LocalPalette provides p, LocalReveal provides 1f) {
                    Column(Modifier.fillMaxSize().background(p.bar).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
                        SheetContent(sheet, sample(full))
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("shots/sheets/$name.png")
    }

    @Test fun today() = shot("1_heute", 0)
    @Test fun fitness() = shot("2_fitness", 1)
    @Test fun sleep() = shot("3_schlaf", 2)
    @Test fun health() = shot("4_gesundheit", 3)
    @Test fun todayStart() = shot("a_heute_start", 0, full = false)
    @Test fun fitnessStart() = shot("b_fitness_start", 1, full = false)
    @Test fun sleepStart() = shot("c_schlaf_start", 2, full = false)
    @Test fun healthStart() = shot("d_gesundheit_start", 3, full = false)
    @Test fun todayMid() = shot("0_heute_animation", 0, 450)
    @Test @Config(qualifiers = "w411dp-h914dp-night-xxhdpi") fun todayDark() = shot("5_heute_dunkel", 0)
    @Test fun update() = shot("6_update", 0, update = UpdateState.Available(
        ch.widmer.airview.update.Release(99, "9.9", "", "", "Beispiel")))

    @Test fun sRing0() = sheet("ring_schritte", Sheet.Ring(0))
    @Test fun sRing1() = sheet("ring_bewegen", Sheet.Ring(1))
    @Test fun sRing2() = sheet("ring_trainieren", Sheet.Ring(2))
    @Test fun sRecovery() = sheet("erholung", Sheet.Recovery)
    @Test fun sRecoveryStart() = sheet("erholung_start", Sheet.Recovery, full = false)
    @Test fun sSleep() = sheet("schlafwert", Sheet.SleepScore, night = true)
    @Test fun sVital() = sheet("hrv", Sheet.Vital("hrv"))
    @Test fun sLoad() = sheet("belastung", Sheet.Load)
    @Test fun sActivity() = sheet("aktivitaet", Sheet.Activity)
    @Test fun sWorkout() = sheet("training", Sheet.Workout(1))
    @Test fun sSettings() = sheet("einstellungen", Sheet.Settings)
    @Test fun sMonth() = sheet("monat", Sheet.Month)

    @Test fun icon() {
        rule.setContent {
            Box(Modifier.size(216.dp).background(androidx.compose.ui.graphics.Color(0xFF171C22))) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_fg), null, Modifier.fillMaxSize())
            }
        }
        rule.onRoot().captureRoboImage("shots/8_icon.png")
    }

    @Test fun skeleton() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AirTheme { Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { Skeleton() } } }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().captureRoboImage("shots/7_laden.png")
    }
}
