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

    private val limitWords = listOf("usage limit", "limit reached", "hit your limit", "reached your limit",
        "kullanım limit", "limitine ulaş", "sıfırlanacak", "will reset", "resets at", "try again later")
    private val blockWords = listOf("captcha", "verify you are human", "i'm not a robot", "robot değilim", "güvenlik doğrulaması")

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.packageName?.toString() != AutoState.CLAUDE) return
        if (AutoState.activeId == null || AutoState.busy) return
        h.removeCallbacks(tick); h.postDelayed(tick, 1500)
    }

    override fun onInterrupt() {}

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (n == null) return out
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()

    private fun waitAndRetry(id: String, why: String) {
        if (++AutoState.misses >= 8) Runner.pause(this, id, why) else { h.removeCallbacks(tick); h.postDelayed(tick, 2000) }
    }

    private fun attempt() {
        val id = AutoState.activeId ?: return
        if (AutoState.busy) return
        val root = rootInActiveWindow ?: return waitAndRetry(id, "Claude arayüzü okunamadı")
        if (root.packageName?.toString() != AutoState.CLAUDE) return waitAndRetry(id, "Claude ön planda değil")
        val t = Store.load(this).find { it.id == id } ?: return
        if (t.status != Status.CHECKING) return
        if (System.currentTimeMillis() < t.triggerAt - 5000) return

        val nodes = flatten(root)
        val all = nodes.joinToString("\n") { label(it) }.lowercase()

        if (blockWords.any { all.contains(it) }) return Runner.pause(this, id, "Güvenlik doğrulaması / engel algılandı")
        if (t.chatTitle.isBlank() || !all.contains(t.chatTitle.lowercase()))
            return waitAndRetry(id, "Hedef sohbet doğrulanamadı (ekranda '${t.chatTitle}' başlığı görünmüyor); yanlış sohbete gönderim engellendi")
        if (limitWords.any { all.contains(it) }) return Runner.retryLater(this, id, "Limit hâlâ dolu")
        if (nodes.any { label(it).lowercase().let { s -> s == "stop" || s == "durdur" || s.startsWith("stop response") } })
            return Runner.finish(this, id, Status.AWAIT_REPLY, "Claude zaten yanıt veriyor, mesaj gönderilmedi")

        val edit = nodes.firstOrNull { it.className?.contains("EditText") == true && it.isEnabled }
            ?: return waitAndRetry(id, "Mesaj alanı bulunamadı")

        AutoState.busy = true
        Store.update(this, id) { it.copy(status = Status.SENDING) }
        Store.log(this, id, "Mesaj alanı bulundu, yazılıyor")
        val args = Bundle().putCharSequenceAndGet(t.message)
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            AutoState.busy = false
            return Runner.pause(this, id, "Mesaj alanına yazılamadı")
        }
        h.postDelayed({ clickSend(id, t.message) }, 800)
    }

    private fun Bundle.putCharSequenceAndGet(s: String): Bundle {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s); return this
    }

    private fun clickSend(id: String, msg: String) {
        val nodes = flatten(rootInActiveWindow)
        val btn = nodes.firstOrNull {
            val d = it.contentDescription?.toString()?.lowercase() ?: ""
            it.isEnabled && (d.contains("send") || d.contains("gönder"))
        } ?: return Runner.pause(this, id, "Gönder düğmesi bulunamadı (mesaj gönderilmedi)")
        var n: AccessibilityNodeInfo? = btn
        while (n != null && !n.isClickable) n = n.parent
        if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Runner.pause(this, id, "Gönder düğmesine tıklanamadı")
        Store.log(this, id, "Gönder düğmesine tıklandı, doğrulanıyor")
        h.postDelayed({ verify(id, msg) }, 2500)   // tekrar GÖNDERİM yok: bundan sonra sadece doğrulama
    }

    private fun verify(id: String, msg: String) {
        val nodes = flatten(rootInActiveWindow)
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
