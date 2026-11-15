package ch.widmer.airview.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Zusammenfassung eines Tages – Grundlage für Ringe, Belastung, Trends und Widget. */
data class DaySummary(
    val date: LocalDate,
    val steps: Long = 0,
    val distanceM: Double = 0.0,
    val totalKcal: Double = 0.0,
    val activeKcal: Double = 0.0,
    /** true, wenn die aktiven kcal aus Gesamt minus Grundumsatz geschätzt sind. */
    val activeEstimated: Boolean = false,
    val trainingMin: Int = 0,
    val zoneMin: IntArray = IntArray(5),
    val trimp: Double = 0.0,
    /** Minuten mit Pulswerten – zeigt, ob das Band getragen wurde. */
    val hrMinutes: Int = 0,
) {
    val zoneTotal get() = zoneMin.sum()

    fun toJson() = JSONObject().apply {
        put("d", date.toString()); put("s", steps); put("km", distanceM); put("tk", totalKcal)
        put("ak", activeKcal); put("ae", activeEstimated); put("tm", trainingMin)
        put("z", JSONArray(zoneMin.toList())); put("t", trimp); put("hm", hrMinutes)
    }

    companion object {
        fun fromJson(o: JSONObject) = DaySummary(
            LocalDate.parse(o.getString("d")), o.getLong("s"), o.getDouble("km"), o.getDouble("tk"),
            o.getDouble("ak"), o.getBoolean("ae"), o.getInt("tm"),
            o.getJSONArray("z").let { a -> IntArray(5) { a.getInt(it) } }, o.getDouble("t"), o.getInt("hm"),
        )
    }
}

/** Heute im Detail: Werte pro Stunde für die Ring-Detailansichten. */
data class TodayHours(
    val steps: IntArray = IntArray(24),
    val activeKcal: DoubleArray = DoubleArray(24),
    val trainingMin: IntArray = IntArray(24),
)

/**
 * Lokaler Speicher abgeschlossener Tage (alles vor gestern), damit nicht jedes Mal
 * Wochen an Pulsdaten neu gelesen werden. Ändern sich Quellen oder Profil, wird neu gerechnet.
 */
class DayCache(context: Context) {
    private val file = File(context.filesDir, "days.json")

    fun load(key: String): Map<LocalDate, DaySummary> = runCatching {
        val o = JSONObject(file.readText())
        if (o.optString("key") != key) return emptyMap()
        val a = o.getJSONArray("days")
        (0 until a.length()).map { DaySummary.fromJson(a.getJSONObject(it)) }.associateBy { it.date }
    }.getOrDefault(emptyMap())

    fun save(key: String, days: Collection<DaySummary>) {
        val o = JSONObject().put("key", key).put("days", JSONArray(days.sortedBy { it.date }.map { it.toJson() }))
        file.writeText(o.toString())
    }
}

/** Kleiner Stand für das Widget (von App und Hintergrund-Sync geschrieben). */
object WidgetState {
    private fun file(c: Context) = File(c.filesDir, "widget.json")
    fun save(c: Context, today: DaySummary, goals: Goals) = runCatching {
        file(c).writeText(today.toJson().put("gs", goals.steps).put("gm", goals.moveKcal).put("ge", goals.exerciseMin).toString())
    }
    fun load(c: Context): Pair<DaySummary, Goals>? = runCatching {
        val o = JSONObject(file(c).readText())
        DaySummary.fromJson(o) to Goals(o.getInt("gs"), o.getInt("gm"), o.getInt("ge"))
    }.getOrNull()
}

/** Gewohnheiten pro Tag (Ja/Nein), für den Zusammenhang mit der Erholung am nächsten Morgen. */
object JournalStore {
    val HABITS = listOf("Alkohol", "Koffein nach 14 Uhr", "Spät gegessen", "Bildschirm im Bett", "Training am Abend")
    private fun file(c: Context) = File(c.filesDir, "journal.json")

    fun load(c: Context): Map<LocalDate, Map<String, Boolean>> = runCatching {
        val o = JSONObject(file(c).readText())
        o.keys().asSequence().associate { d ->
            val e = o.getJSONObject(d)
            LocalDate.parse(d) to e.keys().asSequence().associateWith { e.getBoolean(it) }
        }
    }.getOrDefault(emptyMap())

    fun set(c: Context, date: LocalDate, habit: String, value: Boolean?) {
        val all = load(c).mapValues { it.value.toMutableMap() }.toMutableMap()
        val day = all.getOrPut(date) { mutableMapOf() }
        if (value == null) day.remove(habit) else day[habit] = value
        val o = JSONObject()
        all.forEach { (d, m) -> if (m.isNotEmpty()) o.put(d.toString(), JSONObject(m as Map<*, *>)) }
        file(c).writeText(o.toString())
    }
}

data class HabitInsight(val habit: String, val withMean: Double, val withoutMean: Double, val yes: Int, val no: Int) {
    val diff get() = withMean - withoutMean
}

/**
 * Zusammenhang einer Gewohnheit (am Abend) mit der Erholung am nächsten Morgen.
 * Erst ab je 5 Ja- und 5 Nein-Tagen in den letzten 90 Tagen (wie bei WHOOP).
 */
fun habitInsights(journal: Map<LocalDate, Map<String, Boolean>>, recovery: Map<LocalDate, Int>, today: LocalDate): List<HabitInsight> =
    JournalStore.HABITS.mapNotNull { h ->
        val pairs = journal.filterKeys { it > today.minusDays(90) }.mapNotNull { (d, m) ->
            val v = m[h] ?: return@mapNotNull null
            val r = recovery[d.plusDays(1)] ?: return@mapNotNull null
            v to r
        }
        val yes = pairs.filter { it.first }.map { it.second.toDouble() }
        val no = pairs.filter { !it.first }.map { it.second.toDouble() }
        if (yes.size < 5 || no.size < 5) null else HabitInsight(h, yes.average(), no.average(), yes.size, no.size)
    }
