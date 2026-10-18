package com.sansar.autocontinue

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.UUID

const val DEFAULT_MSG = "Kaldığın yerden devam et. Önceki konuşmadaki talimatları ve mevcut ilerlemeyi koru. Yarım kalan işleri tamamlamak için çalışmayı sürdür. Gereksiz yere onay bekleme; güvenli ve doğru kararları kendin ver. Hata alırsan incele ve mümkünse düzelt. Proje tamamlanmadıysa tamamlandı deme."

sealed interface Screen {
    object Home : Screen
    object Create : Screen
    object Settings : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val tasks by Store.flow(ctx).collectAsState(emptyList())
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    when (val s = screen) {
        Screen.Home -> Home(tasks) { screen = it }
        Screen.Create -> Create { screen = Screen.Home }
        Screen.Settings -> SettingsScreen()
        is Screen.Detail -> tasks.find { it.id == s.id }?.let { Detail(it) { screen = Screen.Home } } ?: run { screen = Screen.Home }
    }
}

@Composable
fun Home(tasks: List<Task>, go: (Screen) -> Unit) {
    Column(Modifier.systemBarsPadding().padding(16.dp)) {
        Text("Claude AutoContinue", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("Uyarı: Ekran kapalı/kilitliyken mesaj gönderimi garanti DEĞİLDİR. Android kilitli ekranda başka uygulamaların arayüzüne erişimi engelleyebilir; bu durumda görev bir sonraki kontrolde yeniden denenir.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go(Screen.Create) }) { Text("Yeni görev") }
            OutlinedButton({ go(Screen.Settings) }) { Text("İzinler ve ayarlar") }
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(tasks, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { go(Screen.Detail(t.id)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text(t.status.tr)
                        Text("Planlanan: ${fmt(t.triggerAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

fun pick(ctx: Context, cur: Long, done: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = cur }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, mi ->
            c.set(y, m, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0); done(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

fun openClaude(ctx: Context) {
    ctx.packageManager.getLaunchIntentForPackage(AutoState.CLAUDE)?.let { ctx.startActivity(it) }
}

@Composable
fun Create(back: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf(DEFAULT_MSG) }
    var at by remember { mutableStateOf(System.currentTimeMillis() + 3_600_000) }
    var interval by remember { mutableStateOf("15") }
    var max by remember { mutableStateOf("12") }
    var stop by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Yeni görev", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Görev adı") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(chat, { chat = it }, label = { Text("Hedef sohbet başlığı (boş bırakırsan doğrulama yapılmaz, açık sohbete yazar)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton({ openClaude(ctx) }) { Text("Claude'u aç ve sohbeti seç") }
        OutlinedTextField(msg, { msg = it }, label = { Text("Gönderilecek mesaj") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
        OutlinedButton({ pick(ctx, at) { at = it } }) { Text("Tarih/saat: ${fmt(at)}") }
        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Yeniden kontrol aralığı (dk)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(max, { max = it.filter(Char::isDigit) }, label = { Text("Maksimum tekrar sayısı") }, modifier = Modifier.fillMaxWidth())
        Row { Switch(stop, { stop = it }); Spacer(Modifier.width(8.dp)); Text("Mesaj gönderilince görevi tamamla") }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button({
            if (name.isBlank() || msg.isBlank()) { err = "Görev adı ve mesaj zorunlu."; return@Button }
            val t = Task(UUID.randomUUID().toString(), name.trim(), chat.trim(), msg, at,
                interval.toIntOrNull()?.coerceAtLeast(1) ?: 15, max.toIntOrNull()?.coerceAtLeast(1) ?: 12, stop)
            Store.add(ctx, t); Store.log(ctx, t.id, "Görev oluşturuldu"); Scheduler.schedule(ctx, t); back()
        }, Modifier.fillMaxWidth()) { Text("Kaydet ve zamanla") }
    }
}

@Composable
fun Detail(t: Task, back: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.name, style = MaterialTheme.typography.headlineSmall)
        Text("Durum: ${t.status.tr}")
        Text("Sohbet: ${t.chatTitle}")
        Text("Planlanan: ${fmt(t.triggerAt)}  •  Deneme: ${t.attempts}/${t.maxRetries}  •  Aralık: ${t.intervalMin} dk")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                Store.update(ctx, t.id) { it.copy(status = Status.WAITING, attempts = 0, triggerAt = maxOf(it.triggerAt, System.currentTimeMillis() + 5000)) }?.let {
                    Store.log(ctx, t.id, "Kullanıcı başlattı"); Scheduler.schedule(ctx, it)
                }
            }) { Text("Başlat") }
            OutlinedButton({
                Scheduler.cancel(ctx, t.id); if (AutoState.activeId == t.id) AutoState.activeId = null
                Store.update(ctx, t.id) { it.copy(status = Status.PAUSED) }; Store.log(ctx, t.id, "Kullanıcı duraklattı")
            }) { Text("Duraklat") }
            OutlinedButton({
                Scheduler.cancel(ctx, t.id); if (AutoState.activeId == t.id) AutoState.activeId = null
                Store.update(ctx, t.id) { it.copy(status = Status.DONE) }; Store.log(ctx, t.id, "Kullanıcı durdurdu")
            }) { Text("Durdur") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ openClaude(ctx) }) { Text("Claude'u aç") }
            OutlinedButton({ Scheduler.cancel(ctx, t.id); Store.delete(ctx, t.id); back() }) { Text("Sil") }
        }
        Text("Geçmiş ve hata kayıtları", style = MaterialTheme.typography.titleMedium)
        LazyColumn { items(t.log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var pin by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(Pin.get(ctx) != null) }
    var relock by remember { mutableStateOf(Pin.relock(ctx)) }
    fun go(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    Column(Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("İzinler ve ayarlar", style = MaterialTheme.typography.headlineSmall)
        Text("Erişilebilirlik: Bu uygulama yalnızca Claude uygulamasının ekranını okur ve mesaj alanına yazıp Gönder'e basar. Parola/çerez toplamaz, hiçbir veri sunucuya gitmez. Ayarlarda 'Claude AutoContinue' hizmetini açın.", style = MaterialTheme.typography.bodySmall)
        Button({ go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Erişilebilirlik ayarlarını aç") }
        Text("Pil: Pil optimizasyonu arka plandaki alarmı geciktirebilir. Bu uygulamayı 'Kısıtlama yok / Optimize etme' olarak ayarlayın.", style = MaterialTheme.typography.bodySmall)
        Button({ go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Pil optimizasyonu ayarları") }
        Text("Tam zamanlı alarm: Planlanan dakikada çalışması için gerekir (Android 12+).", style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 31) Button({ go(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }) { Text("Tam zamanlı alarm izni") }
        Text("Bildirim ve tam ekran bildirimi: Telefon kilitliyken Claude'u öne getirmeye çalışmak için kullanılır; Android buna her zaman izin vermez.", style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 33) Button({ notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Bildirim izni") }
        if (Build.VERSION.SDK_INT >= 34) Button({ go(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) }) { Text("Tam ekran bildirim izni") }
        Text("Kilit açma (isteğe bağlı): Ekran kilitliyse uygulama ekranı uyandırıp rakamlı PIN'inizi girmeyi dener. PIN, Android Keystore ile şifrelenip yalnızca bu telefonda saklanır. Yalnızca rakamlı PIN desteklenir (desen/harfli şifre değil). Çalışması telefonun kilit ekranı arayüzüne bağlıdır, garanti değildir.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit) },
            label = { Text(if (has) "PIN kayıtlı (değiştirmek için yazın)" else "Rakamlı PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (pin.length >= 4) { Pin.save(ctx, pin); pin = ""; has = true } }) { Text("PIN'i kaydet") }
            OutlinedButton({ Pin.clear(ctx); has = false }) { Text("PIN'i sil") }
        }
        Row { Switch(relock, { relock = it; Pin.setRelock(ctx, it) }); Spacer(Modifier.width(8.dp)); Text("Telefonu ben açtıysam, gönderimden sonra ekranı tekrar kilitle") }
    }
}
