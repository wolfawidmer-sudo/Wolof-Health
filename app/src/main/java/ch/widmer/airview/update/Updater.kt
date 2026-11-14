package ch.widmer.airview.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ch.widmer.airview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Kleiner Updater für den Privatgebrauch.
 *
 * Liest version.json aus dem GitHub-Repo:
 *   { "versionCode": 4, "versionName": "1.3", "apk": "https://…/wolof-health.apk",
 *     "sha256": "…", "notes": "Was neu ist" }
 * Ist versionCode höher als die installierte Version, wird die APK geladen,
 * per SHA-256 geprüft und über den System-Installer installiert. Android zeigt
 * dabei immer noch eine Bestätigung – ohne sie kann keine App sich selbst ersetzen.
 */
data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val notes: String)

object Updater {
    private const val ACTION = "ch.widmer.airview.INSTALL_STATUS"

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val con = (URL(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; useCaches = false
        }
        try {
            if (con.responseCode != 200) return@withContext null
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val r = Release(j.getInt("versionCode"), j.getString("versionName"), j.getString("apk"),
                j.getString("sha256").lowercase(), j.optString("notes"))
            if (r.versionCode > BuildConfig.VERSION_CODE) r else null
        } finally { con.disconnect() }
    }

    /** Lädt die APK, meldet Fortschritt 0..1 und gibt die geprüfte Datei zurück. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "update.apk")
        val con = URL(r.apk).openConnection() as HttpURLConnection
        con.instanceFollowRedirects = true
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = con.contentLengthLong.takeIf { it > 0 } ?: -1L
            con.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally { con.disconnect() }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        check(hash == r.sha256) { "Prüfsumme stimmt nicht – Download abgebrochen." }
        out
    }

    /** Muss der Nutzer «Unbekannte Apps installieren» erst erlauben? */
    fun needsInstallPermission(ctx: Context) =
        Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
            val pi = PendingIntent.getBroadcast(ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(pi.intentSender)
        }
    }
}

/** Empfängt den Status der Installation; zeigt bei Bedarf die System-Bestätigung an. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(ctx::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // App wird neu gestartet
            else -> android.widget.Toast.makeText(ctx,
                "Update nicht installiert: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unbekannter Fehler"),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
