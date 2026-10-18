package com.sansar.autocontinue

import android.content.Context
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// PIN, Android Keystore anahtarıyla şifrelenip yalnızca bu uygulamanın içinde saklanır; dışarı gönderilmez.
object Pin {
    private const val ALIAS = "ac_pin_key"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pin", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun save(ctx: Context, pin: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(pin.toByteArray())
        prefs(ctx).edit().putString("pin", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context): String? = try {
        val s = prefs(ctx).getString("pin", null) ?: return null
        val (iv, ct) = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    } catch (_: Exception) { null }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("pin").apply()
    fun relock(ctx: Context) = prefs(ctx).getBoolean("relock", true)
    fun setRelock(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("relock", v).apply()
}

object Wake {
    private var wl: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    fun acquire(ctx: Context) {
        release()
        val pm = ctx.getSystemService(PowerManager::class.java)
        wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "autocontinue:wake")
            .also { it.acquire(4 * 60 * 1000L) }
    }
    fun release() { try { wl?.release() } catch (_: Exception) {}; wl = null }
}
