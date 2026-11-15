package ch.widmer.airview.data

import android.content.Context

/**
 * Persönliche Angaben für Schätzungen (Grundumsatz, Maximalpuls) und Einstellungen.
 * Alles bleibt lokal in den SharedPreferences.
 */
data class Profile(
    val age: Int = 30,
    val male: Boolean = true,
    val heightCm: Int = 178,
    val weightKg: Double = 75.0,
    val sleepGoalMin: Int = 480,
    val bedtimeReminder: Boolean = false,
    val vitalsAlerts: Boolean = true,
    /** Ob der Nutzer die Werte schon einmal bestätigt hat (sonst Hinweis in den Einstellungen). */
    val confirmed: Boolean = false,
) {
    /** Grundumsatz pro Tag nach Mifflin-St Jeor. */
    val bmrPerDay: Double
        get() = 10 * weightKg + 6.25 * heightCm - 5 * age + if (male) 5 else -161

    /** Maximalpuls nach Tanaka (208 − 0,7 × Alter). */
    val maxHr: Double get() = 208 - 0.7 * age

    /** Fingerabdruck der Werte, die gecachte Tageswerte beeinflussen. */
    val cacheKey: String get() = "$age-$male-$heightCm-${weightKg.toInt()}"
}

object ProfileStore {
    private fun prefs(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(c: Context): Profile = prefs(c).let {
        Profile(
            age = it.getInt("age", 30),
            male = it.getBoolean("male", true),
            heightCm = it.getInt("height", 178),
            weightKg = it.getFloat("weight", 75f).toDouble(),
            sleepGoalMin = it.getInt("sleepGoal", 480),
            bedtimeReminder = it.getBoolean("bedtimeReminder", false),
            vitalsAlerts = it.getBoolean("vitalsAlerts", true),
            confirmed = it.getBoolean("confirmed", false),
        )
    }

    fun save(c: Context, p: Profile) = prefs(c).edit()
        .putInt("age", p.age).putBoolean("male", p.male).putInt("height", p.heightCm)
        .putFloat("weight", p.weightKg.toFloat()).putInt("sleepGoal", p.sleepGoalMin)
        .putBoolean("bedtimeReminder", p.bedtimeReminder).putBoolean("vitalsAlerts", p.vitalsAlerts)
        .putBoolean("confirmed", p.confirmed).apply()
}

data class Goals(val steps: Int = 10_000, val moveKcal: Int = 500, val exerciseMin: Int = 30)

object GoalStore {
    private fun prefs(c: Context) = c.getSharedPreferences("goals", Context.MODE_PRIVATE)
    fun load(c: Context) = prefs(c).let {
        Goals(it.getInt("steps", 10_000), it.getInt("move", 500), it.getInt("ex", 30))
    }
    fun save(c: Context, g: Goals) = prefs(c).edit()
        .putInt("steps", g.steps).putInt("move", g.moveKcal).putInt("ex", g.exerciseMin).apply()
}

