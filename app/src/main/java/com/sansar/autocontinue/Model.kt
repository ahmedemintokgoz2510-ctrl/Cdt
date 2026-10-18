package com.sansar.autocontinue

import org.json.JSONArray
import org.json.JSONObject

enum class Status(val tr: String) {
    PREPARING("Hazırlanıyor"), WAITING("Zamanı bekliyor"), CHECKING("Claude kontrol ediliyor"),
    LIMIT("Limit dolu"), SENDING("Mesaj gönderiliyor"), SENT("Mesaj gönderildi"),
    AWAIT_REPLY("Claude yanıtı bekleniyor"), DONE("Tamamlandı"), PAUSED("Hata nedeniyle duraklatıldı")
}

data class Task(
    val id: String, val name: String, val chatTitle: String, val message: String,
    val triggerAt: Long, val intervalMin: Int, val maxRetries: Int, val stopWhenDone: Boolean,
    val status: Status = Status.WAITING, val attempts: Int = 0, val log: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("chat", chatTitle)
        .put("msg", message).put("at", triggerAt).put("int", intervalMin).put("max", maxRetries)
        .put("stop", stopWhenDone).put("st", status.name).put("att", attempts).put("log", JSONArray(log))

    companion object {
        fun fromJson(o: JSONObject): Task {
            val l = o.getJSONArray("log")
            return Task(o.getString("id"), o.getString("name"), o.getString("chat"), o.getString("msg"),
                o.getLong("at"), o.getInt("int"), o.getInt("max"), o.getBoolean("stop"),
                Status.valueOf(o.getString("st")), o.getInt("att"), List(l.length()) { l.getString(it) })
        }
    }
}
