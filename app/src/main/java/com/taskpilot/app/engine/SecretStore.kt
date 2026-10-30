package com.taskpilot.app.engine

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's lock-screen PIN, kept so a night-time run can unlock the phone.
 *
 * - It is never written in plain text: it is encrypted (AES-256-GCM) with a key that is created
 *   INSIDE the phone's secure hardware (Android Keystore / StrongBox). That key can't be read or
 *   copied out — not by TaskPilot, not by a backup, not by someone copying the app's files.
 * - The encrypted blob lives in the app's "no-backup" folder, and backups / phone-to-phone
 *   transfer are switched off for the whole app (see AndroidManifest), so it never leaves the phone.
 * - It is never part of an automation, the History, a log, a notification or the APK.
 *   Sharing the app, its code or an automation can't carry it along.
 * - It is never shown again after saving — it can only be replaced or removed.
 */
object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "taskpilot.lockscreen.pin.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "lockscreen.pin.enc")

    fun hasPin(ctx: Context): Boolean = file(ctx).exists()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun build(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Must stay usable while the phone is locked — that's the whole point.
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
        return try {
            build(strongBox = true)
        } catch (_: Exception) {
            build(strongBox = false)       // no StrongBox chip → regular hardware-backed Keystore
        }
    }

    /** Saves [pin] (digits only). The caller's array is wiped afterwards. */
    fun savePin(ctx: Context, pin: CharArray): Boolean {
        val bytes = toBytes(pin)
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(bytes)
            val iv = c.iv
            val out = ByteBuffer.allocate(1 + iv.size + enc.size).put(iv.size.toByte()).put(iv).put(enc).array()
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(f)) { f.writeBytes(out); tmp.delete() }
            true
        } catch (_: Exception) {
            false
        } finally {
            Arrays.fill(bytes, 0)
            Arrays.fill(pin, '\u0000')
        }
    }

    /** Returns the PIN, or null. Wipe the returned array when done (Arrays.fill). */
    fun readPin(ctx: Context): CharArray? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val all = f.readBytes()
            val ivLen = all[0].toInt()
            val iv = all.copyOfRange(1, 1 + ivLen)
            val enc = all.copyOfRange(1 + ivLen, all.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = c.doFinal(enc)
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            val out = CharArray(chars.remaining()).also { chars.get(it) }
            Arrays.fill(plain, 0)
            out
        } catch (_: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        try { keyStore().deleteEntry(ALIAS) } catch (_: Exception) {}
    }

    private fun toBytes(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining()).also { bb.get(it) }
        if (bb.hasArray()) Arrays.fill(bb.array(), 0)
        return out
    }
}
