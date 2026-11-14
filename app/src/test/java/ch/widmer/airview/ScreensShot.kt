package ch.widmer.airview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.health.connect.client.records.SleepSessionRecord as S
import ch.widmer.airview.data.*
import ch.widmer.airview.ui.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreensShot {
    private val z = ZoneId.systemDefault()
    private val today = LocalDate.now(z)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(z).toInstant()

    private fun sample(): Snapshot {
        val night = today.minusDays(1)
        val stages = listOf(
            0 to S.STAGE_TYPE_LIGHT, 25 to S.STAGE_TYPE_DEEP, 80 to S.STAGE_TYPE_LIGHT, 140 to S.STAGE_TYPE_REM,
            165 to S.STAGE_TYPE_AWAKE, 172 to S.STAGE_TYPE_LIGHT, 230 to S.STAGE_TYPE_DEEP, 270 to S.STAGE_TYPE_LIGHT,
            330 to S.STAGE_TYPE_REM, 370 to S.STAGE_TYPE_LIGHT, 420 to S.STAGE_TYPE_REM, 455 to S.STAGE_TYPE_AWAKE, 462 to -1,
        )
        val st0 = night.atTime(23, 12).atZone(z).toInstant()
        val sl = stages.zipWithNext().map { (a, b) -> SleepStage(st0.plusSeconds(a.first * 60L), st0.plusSeconds(b.first * 60L), a.second) }
        val nights = (13 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            val s = d.minusDays(1).atTime(23, 0).atZone(z).toInstant().plusSeconds((i * 731 % 60) * 60L)
            val len = 380L + (i * 37 % 90)
            if (i == 0) SleepNight(d, st0, st0.plusSeconds(462 * 60), sl) else SleepNight(d, s, s.plusSeconds(len * 60), emptyList())
        }
        val now = Instant.now()
        val hr = generateSequence(at(today, 0)) { it.plusSeconds(120) }.takeWhile { it < now }.map {
            val h = Duration.between(at(today, 0), it).toMinutes() / 60.0
            val base = if (h < 7.4) 54 + 3 * sin(h * 3) else 72 + 8 * sin(h * 1.3)
            val walk = if (h in 8.0..8.6 || h in 12.2..12.9) 38.0 else 0.0
            HrPoint(it, (base + walk + (it.epochSecond % 7)).toLong())
        }.toList()
        fun series(n: Int, base: Double, amp: Double) = (n - 1 downTo 0).map { DayValue(today.minusDays(it.toLong()), base + amp * sin(it * 1.7)) }
        return Snapshot(
            steps = 7_842, distanceM = 5_930.0, activeKcal = 412.0, totalKcal = 1_960.0, floors = 9.0, activeMinutes = 47,
            hrToday = hr,
            stepsWeek = listOf(11_204, 6_310, 9_870, 12_455, 4_120, 10_380, 7_842).mapIndexed { i, v -> DayValue(today.minusDays(6L - i), v.toDouble()) },
            kcalWeek = listOf(2410, 2050, 2280, 2600, 1890, 2330, 1960).mapIndexed { i, v -> DayValue(today.minusDays(6L - i), v.toDouble()) },
            workouts = listOf(
                Workout("Gehen", 79, at(today, 8, 2), at(today, 8, 38), 168.0, 2_940.0, 104),
                Workout("Laufen", 56, at(today.minusDays(2), 18, 10), at(today.minusDays(2), 18, 58), 512.0, 8_210.0, 151),
                Workout("Velo", 8, at(today.minusDays(4), 7, 30), at(today.minusDays(4), 8, 5), 290.0, 11_400.0, 128),
            ),
            sleep = nights, restingHr = series(30, 56.0, 2.5), hrv = series(1, 89.0, 0.0),
            rings = listOf(
                RingDay(today.minusDays(6), 11_204, 610.0, 52), RingDay(today.minusDays(5), 6_310, 280.0, 12),
                RingDay(today.minusDays(4), 9_870, 540.0, 41), RingDay(today.minusDays(3), 12_455, 720.0, 66),
                RingDay(today.minusDays(2), 4_120, 190.0, 8), RingDay(today.minusDays(1), 10_380, 505.0, 31),
                RingDay(today, 7_057, 315.0, 53),
            ),
            sources = listOf(Source("com.fitbit.FitbitMobile", "Google Health", today.minusDays(1), 2),
                Source("com.android.healthconnect.phone.x", "Handy", today.minusDays(29), 30)),
            activeSources = setOf("com.fitbit.FitbitMobile", "com.android.healthconnect.phone.x"),
            spo2 = emptyList(), respRate = series(30, 14.6, 0.5), vo2max = 46.0, weightKg = 74.3,
        )
    }

    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, page: Int, at: Long = 2500, update: UpdateState = UpdateState.Idle) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AirTheme { Pages(sample(), false, {}, {}, startPage = page, update = update) } }
        rule.mainClock.advanceTimeBy(at)
        rule.onRoot().captureRoboImage("shots/$name.png")
    }

    @Test fun today() = shot("1_heute", 0)
    @Test fun skeleton() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AirTheme { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { Skeleton() } } }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().captureRoboImage("shots/7_laden.png")
    }
    @Test fun todayMid() = shot("0_heute_animation", 0, 450)
    @Test fun todayMid2() = shot("0_heute_animation2", 0, 800)
    @Test fun fitness() = shot("2_fitness", 1)
    @Test fun sleep() = shot("3_schlaf", 2)
    @Test fun health() = shot("4_gesundheit", 3)
    @Test fun update() = shot("6_update", 0, update = UpdateState.Available(
        ch.widmer.airview.update.Release(5, "1.4", "", "", "Ringe zeigen jetzt auch die Woche im Detail.")))
    @Test @Config(qualifiers = "w411dp-h914dp-night-xxhdpi") fun todayDark() = shot("5_heute_dunkel", 0)
}
