package uk.mwms1488.pdfshoot

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Новіша версія на сервері: номер, назва, де лежить APK, що змінилось. */
data class Update(val versionCode: Long, val versionName: String, val url: String, val notes: String)

/**
 * Оновлення без магазину, як в Inertia: поряд з APK на сервері лежить `version.json`; якщо його
 * versionCode вищий за встановлений, APK завантажується й ставиться через [PackageInstaller].
 * На Android 12+ застосунок оновлює сам себе без запитань, щойно користувач один раз дозволив
 * йому встановлювати застосунки; на старших Android система щоразу питає «Оновити?».
 *
 * Перевіряємо при кожному поверненні в застосунок (не частіше ніж раз на [CHECK_EVERY_MS]) і раз
 * на кілька годин у фоні ([UpdateJob]). Встановлення закриває застосунок, тому з екрана воно
 * починається лише тоді, коли користувач нічим не зайнятий ([hold]).
 */
object Updater {
    const val SITE = "https://92-5-101-235.sslip.io/pdfshoot/"

    /** Стале посилання на останній APK — ним діляться з меню. */
    const val APK_LINK = SITE + "pdfshoot.apk"

    private const val TAG = "Updater"
    private const val PREFS = "updater"
    private val CHECK_EVERY_MS = TimeUnit.MINUTES.toMillis(10)

    enum class Outcome { Disabled, Throttled, Unreachable, UpToDate, Postponed, Installing, Failed }

    /** Екран застосунку: показує запит Android (дозволити встановлення) у власному вікні. */
    @Volatile var asker: ((Intent) -> Unit)? = null

    /** Користувач сканує, вводить назву чи дивиться PDF: зараз застосунок закривати не можна. */
    @Volatile var hold: Boolean = false

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** Debug-збірка має інший ідентифікатор пакета й себе не оновлює. */
    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    /** Android 12+, і встановлення з цього застосунку дозволено: оновлення йде без запитань. */
    fun silent(context: Context) = Build.VERSION.SDK_INT >= 31 && context.packageManager.canRequestPackageInstalls()

    fun installedCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Увесь цикл в окремому потоці; [onOutcome] приходить у головний потік. */
    fun start(context: Context, visible: Boolean, force: Boolean, onOutcome: (Outcome) -> Unit = {}) {
        val app = context.applicationContext
        Thread({
            val outcome = runCatching { run(app, visible, force) }.getOrElse {
                Log.w(TAG, "update", it)
                Outcome.Failed
            }
            main.post { onOutcome(outcome) }
        }, "pdfshoot-update").start()
    }

    /** Перевірити, завантажити, встановити. Блокує потік. [force] — не зважати на паузу між перевірками. */
    fun run(context: Context, visible: Boolean, force: Boolean): Outcome {
        if (!enabled(context)) return Outcome.Disabled
        if (!running.compareAndSet(false, true)) return Outcome.Throttled
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // Час «із майбутнього» (годинник перевели назад) не блокує перевірки назавжди.
            if (!force && now - prefs.getLong("checked", 0) in 0 until CHECK_EVERY_MS) return Outcome.Throttled
            prefs.edit().putLong("checked", now).apply()

            val update = try {
                fetch()
            } catch (e: Exception) {
                return Outcome.Unreachable
            }
            if (update.versionCode <= installedCode(context) || update.url.isBlank()) return Outcome.UpToDate

            // Без користувача перед екраном ставимо лише мовчки, і лише версію, яка ще не підвела.
            if (!visible && (!silent(context) || prefs.getLong("failed", 0) >= update.versionCode || inUse())) {
                return Outcome.Postponed
            }
            val apk = download(context, update)

            // Чекаємо, доки користувач закінчить сканувати чи читати: оновлення закриває застосунок.
            var waited = 0
            while (hold && inUse()) {
                if (waited++ > 600) return Outcome.Postponed
                Thread.sleep(1000)
            }
            val onScreen = inUse()
            if (!onScreen && !silent(context)) return Outcome.Postponed // запит Android нікому показати
            if (onScreen) main.post {
                Toast.makeText(
                    context,
                    "Оновлення до ${update.versionName} — відкрийте застосунок знову",
                    Toast.LENGTH_LONG,
                ).show()
            }
            install(context, apk, update.versionCode, visible = onScreen, retried = false)
            return Outcome.Installing
        } finally {
            running.set(false)
        }
    }

    private fun fetch(): Update {
        val connection = URL(SITE + "version.json").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return Update(json.getLong("versionCode"), json.optString("versionName"), json.optString("url"), json.optString("notes"))
        } finally {
            connection.disconnect()
        }
    }

    private fun apkFile(context: Context, versionCode: Long) = File(File(context.cacheDir, "update"), "pdfshoot-$versionCode.apk")

    /** APK у кеші застосунку; завантажений раніше вдруге не качається. */
    private fun download(context: Context, update: Update): File {
        val apk = apkFile(context, update.versionCode)
        if (apk.exists()) return apk
        val dir = apk.parentFile!!.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // старі версії
        val part = File(dir, apk.name + ".part")
        val connection = URL(update.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode} ${update.url}")
            val length = connection.contentLengthLong
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (length >= 0 && part.length() != length) throw IOException("${part.length()} з $length байтів")
            if (!part.renameTo(apk)) throw IOException("rename $part")
            return apk
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Пише APK у сесію встановлення й підтверджує її. Відповідь Android приходить в [InstallReceiver]. */
    fun install(context: Context, apk: File, versionCode: Long, visible: Boolean, retried: Boolean) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val status = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION_STATUS)
                    .putExtra(InstallReceiver.EXTRA_VISIBLE, visible)
                    .putExtra(InstallReceiver.EXTRA_CODE, versionCode)
                    .putExtra(InstallReceiver.EXTRA_RETRIED, retried)
                // Mutable: Android дописує в intent свою відповідь.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Той самий APK ще раз: Android 16 скасовує встановлення на шляху назад із Налаштувань. */
    fun installAgain(context: Context, versionCode: Long) {
        val apk = apkFile(context, versionCode)
        if (!apk.exists()) return
        Thread({ runCatching { install(context, apk, versionCode, visible = true, retried = true) } }, "pdfshoot-install").start()
    }

    fun markFailed(context: Context, versionCode: Long) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("failed", versionCode).apply()

    /** Застосунок на екрані. */
    private fun inUse(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}

/** Відповідь Android на сесію встановлення і перший запуск нової версії після неї. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                File(context.cacheDir, "update").deleteRecursively()
                UpdateJob.schedule(context)
            }
            ACTION_STATUS -> status(context, intent)
        }
    }

    private fun status(context: Context, intent: Intent) {
        val visible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
        val code = intent.getLongExtra(EXTRA_CODE, 0)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (visible && confirm != null) {
                    // Запит Android: дозволити встановлення з PDF Shoot або (до Android 12) «Оновити?».
                    val asker = Updater.asker
                    if (asker != null) asker(confirm)
                    else runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else {
                    // Відповісти нікому: відкладаємо до наступного відкриття застосунку.
                    val installer = context.packageManager.packageInstaller
                    runCatching { installer.abandonSession(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)) }
                    Updater.markFailed(context, code)
                }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.w("Updater", "install status $status: $message")
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // Дозвіл щойно дали в Налаштуваннях, а встановлення скасувалось: ще раз, уже без запиту.
                    val retried = intent.getBooleanExtra(EXTRA_RETRIED, false)
                    if (visible && !retried && Updater.silent(context)) Updater.installAgain(context, code)
                    return // інакше — «Скасувати» на запит Android
                }
                if (visible) Toast.makeText(context, "Оновлення не встановилось: ${message.ifBlank { "помилка $status" }}", Toast.LENGTH_LONG).show()
                else Updater.markFailed(context, code)
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "uk.mwms1488.pdfshoot.action.INSTALL_STATUS"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_CODE = "versionCode"
        const val EXTRA_RETRIED = "retried"
    }
}

/** Фонова перевірка раз на кілька годин: поки застосунком не користуються, він оновлюється сам. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            runCatching { Updater.run(applicationContext, visible = false, force = true) }
            jobFinished(params, false)
        }, "pdfshoot-update-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            if (!Updater.enabled(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(3))
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
