package com.sansar.autocontinue

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClaudeAccessibilityService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = Runnable { attempt() }
    @Volatile private var lastTop: AccessibilityNodeInfo? = null
    private var drawerKey = ""
    private var drawerClicks = 0

    private val limitWords = listOf("usage limit", "limit reached", "hit your limit", "reached your limit",
        "kullanım limit", "limitine ulaş", "sıfırlanacak", "will reset", "resets at", "try again later")
    private val blockWords = listOf("captcha", "verify you are human", "i'm not a robot", "robot değilim", "güvenlik doğrulaması")

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (AutoState.activeId == null || AutoState.busy) return
        val src = e?.source
        if (src != null && src.packageName?.toString() == AutoState.CLAUDE) {
            var r: AccessibilityNodeInfo = src
            while (r.parent != null) r = r.parent
            lastTop = r
        } else if (e?.packageName?.toString() != AutoState.CLAUDE && e?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        h.removeCallbacks(tick); h.postDelayed(tick, 1500)
    }

    override fun onInterrupt() {}

    // Claude penceresini birkaç yoldan bulmaya çalışır
    private fun claudeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() == AutoState.CLAUDE) return it }
        try {
            for (w in windows) { val r = w.root ?: continue; if (r.packageName?.toString() == AutoState.CLAUDE) return r }
        } catch (_: Exception) {}
        return lastTop?.also { it.refresh() }
    }

    private fun winInfo(): String = try {
        "aktif=${rootInActiveWindow?.packageName}; pencereler=" + windows.joinToString(",") { "${it.root?.packageName}" }
    } catch (e: Exception) { "pencere bilgisi alınamadı" }

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (n == null) return out
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()

    // Hata ayıklama: ekranda görünen öğelerin kısa dökümü
    private fun dump(nodes: List<AccessibilityNodeInfo>): String =
        nodes.filter { label(it).isNotBlank() || it.className?.contains("EditText") == true }
            .take(40).joinToString(" | ") { "${it.className?.toString()?.substringAfterLast('.')}:${label(it).take(25)}" }.take(700)

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        return x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun waitAndRetry(id: String, why: () -> String) {
        if (++AutoState.misses >= 8) Runner.pause(this, id, why()) else { h.removeCallbacks(tick); h.postDelayed(tick, 2000) }
    }

    private fun attempt() {
        val id = AutoState.activeId ?: return
        if (AutoState.busy) return
        val root = claudeRoot() ?: return waitAndRetry(id) { "Claude arayüzü okunamadı. ${winInfo()}" }
        val t = Store.load(this).find { it.id == id } ?: return
        if (t.status != Status.CHECKING) return
        if (System.currentTimeMillis() < t.triggerAt - 5000) return

        val key = "$id#${t.attempts}"
        if (key != drawerKey) { drawerKey = key; drawerClicks = 0 }

        val nodes = flatten(root)
        val all = nodes.joinToString("\n") { label(it) }.lowercase()

        if (blockWords.any { all.contains(it) }) return Runner.pause(this, id, "Güvenlik doğrulaması / engel algılandı")

        // Claude kenar menüsüyle açıldıysa hedef sohbeti listeden seç
        val drawer = all.contains("son kullanılanlar") || all.contains("recents") ||
            (all.contains("yeni sohbet") && all.contains("projeler"))
        if (drawer && drawerClicks < 2) {
            val item = nodes.firstOrNull { label(it).equals(t.chatTitle, ignoreCase = true) }
            if (item != null && clickNode(item)) {
                drawerClicks++
                Store.log(this, id, "Kenar menüsünden sohbet seçildi")
                h.removeCallbacks(tick); h.postDelayed(tick, 2500)
                return
            }
        }

        val title = t.chatTitle.lowercase()
        val short = if (title.length > 18) title.take(18) else title
        if (t.chatTitle.isBlank() || !(all.contains(title) || all.contains(short))) {
            val d = dump(nodes)
            return waitAndRetry(id) { "Hedef sohbet doğrulanamadı; yanlış sohbete gönderim engellendi. Ekran: $d" }
        }
        if (limitWords.any { all.contains(it) }) return Runner.retryLater(this, id, "Limit hâlâ dolu")
        if (nodes.any { label(it).lowercase().let { s -> s == "stop" || s == "durdur" || s.startsWith("stop response") } })
            return Runner.finish(this, id, Status.AWAIT_REPLY, "Claude zaten yanıt veriyor, mesaj gönderilmedi")

        val edit = nodes.firstOrNull { it.className?.contains("EditText") == true && it.isEnabled }
            ?: return waitAndRetry(id) { "Mesaj alanı bulunamadı. Ekran: ${dump(nodes)}" }

        AutoState.busy = true
        Store.update(this, id) { it.copy(status = Status.SENDING) }
        Store.log(this, id, "Mesaj alanı bulundu, yazılıyor")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t.message) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return Runner.pause(this, id, "Mesaj alanına yazılamadı")
        }
        h.postDelayed({ clickSend(id, t.message) }, 800)
    }

    private fun clickSend(id: String, msg: String) {
        val nodes = flatten(claudeRoot())
        val btn = nodes.firstOrNull {
            val d = it.contentDescription?.toString()?.lowercase() ?: ""
            it.isEnabled && (d.contains("send") || d.contains("gönder"))
        } ?: return Runner.pause(this, id, "Gönder düğmesi bulunamadı (mesaj gönderilmedi). Ekran: ${dump(nodes)}")
        if (!clickNode(btn)) return Runner.pause(this, id, "Gönder düğmesine tıklanamadı")
        Store.log(this, id, "Gönder düğmesine tıklandı, doğrulanıyor")
        h.postDelayed({ verify(id, msg) }, 2500)   // tekrar GÖNDERİM yok
    }

    private fun verify(id: String, msg: String) {
        val nodes = flatten(claudeRoot())
        if (nodes.isEmpty()) return Runner.pause(this, id, "Gönderim doğrulanamadı (ekran okunamadı); tekrar denenmedi")
        val edit = nodes.firstOrNull { it.className?.contains("EditText") == true }
        val stillTyped = edit?.text?.toString().orEmpty().startsWith(msg.take(20))
        if (!stillTyped) {
            val t = Store.load(this).find { it.id == id }
            Store.log(this, id, "Gönderim doğrulandı (mesaj alanı temizlendi)")
            Runner.finish(this, id, if (t?.stopWhenDone == true) Status.DONE else Status.AWAIT_REPLY,
                if (t?.stopWhenDone == true) "Mesaj gönderildi, görev tamamlandı" else "Mesaj gönderildi, Claude yanıtı bekleniyor")
        } else Runner.pause(this, id, "Gönderim doğrulanamadı; çift mesaj olmaması için tekrar denenmedi")
    }
}
