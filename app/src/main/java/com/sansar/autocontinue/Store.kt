package com.sansar.autocontinue

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.ds by preferencesDataStore("autocontinue")

fun fmt(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr")).format(Date(ms))

object Store {
    private val KEY = stringPreferencesKey("tasks")
    private val lock = Any()

    private fun parse(s: String?): List<Task> =
        if (s.isNullOrEmpty()) emptyList() else JSONArray(s).let { a -> List(a.length()) { Task.fromJson(a.getJSONObject(it)) } }

    fun flow(ctx: Context): Flow<List<Task>> = ctx.applicationContext.ds.data.map { parse(it[KEY]) }
    fun load(ctx: Context): List<Task> = runBlocking { parse(ctx.applicationContext.ds.data.first()[KEY]) }
    private fun save(ctx: Context, l: List<Task>) = runBlocking {
        ctx.applicationContext.ds.edit { it[KEY] = JSONArray(l.map { t -> t.toJson() }).toString() }
    }

    fun add(ctx: Context, t: Task) = synchronized(lock) { save(ctx, load(ctx) + t) }
    fun delete(ctx: Context, id: String) = synchronized(lock) { save(ctx, load(ctx).filter { it.id != id }) }
    fun update(ctx: Context, id: String, f: (Task) -> Task): Task? = synchronized(lock) {
        val l = load(ctx)
        val t = l.find { it.id == id } ?: return null
        val n = f(t)
        save(ctx, l.map { if (it.id == id) n else it })
        n
    }
    fun log(ctx: Context, id: String, msg: String) {
        update(ctx, id) { it.copy(log = (it.log + "${fmt(System.currentTimeMillis())}  $msg").takeLast(200)) }
    }
}
