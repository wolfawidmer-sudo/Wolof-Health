package ch.widmer.airview.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.*
import ch.widmer.airview.MainActivity
import ch.widmer.airview.R
import ch.widmer.airview.data.*
import ch.widmer.airview.widget.RingsWidget
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Hinweise als System-Benachrichtigung. */
object Notifier {
    private const val CHANNEL = "hinweise"

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(c)
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }
}

/** Merkt sich, was heute schon gemeldet wurde – jede Art höchstens einmal pro Tag. */
private object Sent {
    private fun p(c: Context) = c.getSharedPreferences("sent", Context.MODE_PRIVATE)
    fun once(c: Context, kind: String, day: LocalDate): Boolean {
        val k = "$kind-$day"
        if (p(c).getBoolean(k, false)) return false
        p(c).edit().putBoolean(k, true).apply(); return true
    }
}

/** Gemeinsame Nacharbeit nach jedem Laden (App oder Hintergrund). */
object AfterLoad {
    suspend fun run(c: Context, s: Snapshot, fromBackground: Boolean) {
        runCatching { RingsWidget().updateAll(c) }
        val p = s.profile
        if (p.vitalsAlerts) {
            val off = outOfRangeToday(s)
            // wie bei Apple: erst ab zwei Werten ausserhalb des Bereichs melden
            if (off.size >= 2 && Sent.once(c, "vitals", LocalDate.now())) {
                Notifier.post(c, 1, "Zwei Nachtwerte ungewohnt",
                    off.joinToString(" und ") { it.label } + " lagen letzte Nacht ausserhalb deines üblichen Bereichs.")
            }
        }
        Scheduler.bedtime(c, if (p.bedtimeReminder) bedtimeFor(s) else null)
    }
}

object Scheduler {
    /** Stündlicher Abgleich (Widget, Hinweise). Liest Health Connect nur mit Hintergrund-Recht. */
    fun periodic(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    /** Erinnerung 30 min vor der empfohlenen Zeit; null = keine. */
    fun bedtime(c: Context, at: java.time.LocalTime?) {
        val wm = WorkManager.getInstance(c)
        if (at == null) { wm.cancelUniqueWork("bedtime"); return }
        val zone = ZoneId.systemDefault()
        var target = LocalDateTime.of(LocalDate.now(), at).minusMinutes(30)
        if (target.isBefore(LocalDateTime.now())) target = target.plusDays(1)
        val delay = Duration.between(LocalDateTime.now(), target).toMinutes().coerceAtLeast(1)
        wm.enqueueUniqueWork(
            "bedtime", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BedtimeWorker>().setInitialDelay(delay, TimeUnit.MINUTES)
                .setInputData(workDataOf("at" to at.toString())).build(),
        )
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val repo = runCatching { HealthRepo(applicationContext) }.getOrNull() ?: return Result.success()
        val bg = runCatching { HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in repo.granted() }.getOrDefault(false)
        if (bg) runCatching { AfterLoad.run(applicationContext, repo.load(), fromBackground = true) }
        else runCatching { RingsWidget().updateAll(applicationContext) }
        return Result.success()
    }
}

class BedtimeWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val at = inputData.getString("at") ?: return Result.success()
        if (ProfileStore.load(applicationContext).bedtimeReminder) {
            val t = java.time.LocalTime.parse(at)
            Notifier.post(applicationContext, 2, "Bald Zeit fürs Bett", "Um %d:%02d ins Bett reicht für deinen Schlafbedarf.".format(t.hour, t.minute))
            Scheduler.bedtime(applicationContext, t)   // morgen wieder
        }
        return Result.success()
    }
}
