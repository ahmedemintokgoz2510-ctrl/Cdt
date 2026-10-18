package com.sansar.autocontinue

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

object AutoState {
    const val CLAUDE = "com.anthropic.claude"
    @Volatile var activeId: String? = null
    @Volatile var busy = false
    @Volatile var misses = 0
    @Volatile var kick: (() -> Unit)? = null
}

object Notifier {
    private const val CH = "ac"
    private fun mgr(ctx: Context): NotificationManager {
        val m = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) m.createNotificationChannel(NotificationChannel(CH, "AutoContinue", NotificationManager.IMPORTANCE_HIGH))
        return m
    }
    fun post(ctx: Context, text: String, launch: Intent? = null, id: Int = 1) {
        try {
            val b = NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Claude AutoContinue").setContentText(text).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            if (launch != null) b.setFullScreenIntent(PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE), true)
            mgr(ctx).notify(id, b.build())
        } catch (_: SecurityException) {}
    }
}

object Scheduler {
    private fun pending(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, id.hashCode(), Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, t: Task) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = maxOf(t.triggerAt, System.currentTimeMillis() + 1000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, t.id))
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, t.id))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, t.id))
        }
        // Yedek: alarm kaybolursa WorkManager 30 sn sonra aynı işi dener (Runner çift çalışmayı engeller)
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-${t.id}", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RunWorker>().setInitialDelay(at - System.currentTimeMillis() + 30_000, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("id" to t.id)).build())
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        WorkManager.getInstance(ctx).cancelUniqueWork("run-$id")
        WorkManager.getInstance(ctx).cancelUniqueWork("timeout-$id")
    }
}

object Runner {
    fun start(ctx: Context, id: String) {
        val t = Store.load(ctx).find { it.id == id } ?: return
        if (t.status != Status.WAITING && t.status != Status.LIMIT) return
        if (System.currentTimeMillis() < t.triggerAt - 5000) return   // saat gelmeden işlem yok
        if (t.attempts >= t.maxRetries) { pause(ctx, id, "Maksimum tekrar sayısına ulaşıldı"); return }
        Store.update(ctx, id) { it.copy(status = Status.CHECKING, attempts = it.attempts + 1) }
        Store.log(ctx, id, "Kontrol #${t.attempts + 1} başladı, Claude açılıyor")
        AutoState.activeId = id; AutoState.busy = false; AutoState.misses = 0
        val intent = ctx.packageManager.getLaunchIntentForPackage(AutoState.CLAUDE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) { pause(ctx, id, "Claude uygulaması bulunamadı"); return }
        Wake.acquire(ctx)   // ekran kapalıysa uyandır
        Notifier.post(ctx, "Claude kontrol ediliyor…", intent, 2)
        try { ctx.startActivity(intent) } catch (_: Exception) {}
        AutoState.kick?.invoke()
        WorkManager.getInstance(ctx).enqueueUniqueWork("timeout-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TimeoutWorker>().setInitialDelay(180, TimeUnit.SECONDS)
                .setInputData(workDataOf("id" to id)).build())
    }

    private fun clear(ctx: Context, id: String) {
        if (AutoState.activeId == id) AutoState.activeId = null
        AutoState.busy = false
        Wake.release()
        WorkManager.getInstance(ctx).cancelUniqueWork("timeout-$id")
    }

    fun retryLater(ctx: Context, id: String, msg: String) {
        val t = Store.update(ctx, id) { it.copy(status = Status.LIMIT, triggerAt = System.currentTimeMillis() + it.intervalMin * 60_000L) } ?: return
        Store.log(ctx, id, "$msg. Sonraki kontrol: ${fmt(t.triggerAt)}")
        clear(ctx, id); Scheduler.schedule(ctx, t)
    }

    fun finish(ctx: Context, id: String, status: Status, msg: String) {
        Store.update(ctx, id) { it.copy(status = status) }
        Store.log(ctx, id, msg); clear(ctx, id)
        Notifier.post(ctx, msg)
    }

    fun pause(ctx: Context, id: String, msg: String) = finish(ctx, id, Status.PAUSED, msg)
}

class RunWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result { Runner.start(applicationContext, inputData.getString("id") ?: return Result.success()); return Result.success() }
}

class TimeoutWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val t = Store.load(applicationContext).find { it.id == id }
        if (t?.status == Status.CHECKING)
            Runner.retryLater(applicationContext, id, "Claude arayüzüne erişilemedi (ekran kilitli veya erişilebilirlik kapalı olabilir)")
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { Runner.start(ctx, i.getStringExtra("id") ?: return) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.load(ctx).filter { it.status in listOf(Status.WAITING, Status.LIMIT, Status.CHECKING) }.forEach { t ->
            val n = Store.update(ctx, t.id) { it.copy(status = if (it.status == Status.CHECKING) Status.LIMIT else it.status) } ?: return@forEach
            Store.log(ctx, t.id, "Cihaz yeniden başladı, görev yeniden zamanlandı")
            Scheduler.schedule(ctx, n)
        }
    }
}
