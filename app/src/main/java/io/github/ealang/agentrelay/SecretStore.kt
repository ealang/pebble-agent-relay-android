package io.github.ealang.agentrelay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest: AES-GCM under a key that lives in the Android Keystore.
 *
 * The point is not to defend against another app — app-private prefs already
 * do that, by uid — but against the data leaving the phone in a backup or a
 * device-to-device copy. A Keystore key cannot be exported or backed up (it
 * sits in the TEE; the OS itself only gets to *use* it), so anything such a
 * copy carries is ciphertext whose key is stranded in this phone's hardware.
 * Backups are off outright (`allowBackup="false"`), which makes this the
 * second layer rather than the only one.
 *
 * This is the same construction Jetpack's EncryptedSharedPreferences used;
 * that wrapper was deprecated in 2024 with the advice to do exactly this
 * directly.
 *
 * If the Keystore key is ever invalidated (a factory reset restore, some
 * OEM security events), decryption fails and the secret reads as absent —
 * the bearer token regenerates and Telegram must be logged in again. That
 * is the designed failure: inconvenient, never insecure.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("agent-relay-secrets", Context.MODE_PRIVATE)

    /**
     * Cache-first. After [warm] has run (or the first read of a name), this
     * never touches the Keystore again for the process's lifetime, so it is
     * safe to call from the UI thread's refresh path.
     */
    fun get(name: String): String? {
        cache[name]?.let { return it.takeIf { v -> v !== NONE } as String? }
        return load(name).also { cache[name] = it ?: NONE }
    }

    /**
     * Returns immediately: the cache is updated now (a get on any thread
     * sees the new value), the Keystore work happens on the store's own
     * thread. The first-ever write also *generates* the wrapping key, and
     * the Keystore daemon can take seconds over that; on the UI thread that
     * would read as the app locking up.
     */
    fun put(name: String, value: String?) {
        cache[name] = value ?: NONE
        executor.execute {
            try {
                persist(name, value)
            } catch (e: Exception) {
                // The value is still live in the cache, so the app keeps
                // working; it just won't survive the process.
                Log.e(TAG, "Could not persist '$name'", e)
            }
        }
    }

    private fun load(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key no longer opens this blob. Stale ciphertext would fail
            // this way forever, so clear it and report the secret as unset.
            Log.w(TAG, "Could not decrypt '$name'; treating it as unset", e)
            prefs.edit { remove(name) }
            null
        }
    }

    private fun persist(name: String, value: String?) {
        if (value == null) {
            prefs.edit { remove(name) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    /** The wrapping key, generated inside the Keystore on first use. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-auth requirement: these secrets are read by a
                // service on a locked phone; a key gated on unlock would
                // take the server down whenever the screen is off.
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /**
         * One cache and one worker for the process, because Config makes a
         * fresh SecretStore per use — per-instance state would cache nothing.
         * The NONE sentinel is how "known to be unset" is told apart from
         * "never looked".
         */
        private val cache = ConcurrentHashMap<String, Any>()
        private val NONE = Any()
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "secret-store")
        }

        @Volatile
        var ready: Boolean = false
            private set

        /**
         * Force the expensive first contact with the Keystore — daemon
         * round-trip, and on the very first launch the key generation — off
         * the UI thread. Call it early (activity and service onCreate);
         * [onReady], posted to the main thread, is the moment reads are
         * jank-free.
         */
        fun warm(context: Context, onReady: () -> Unit = {}) {
            val main = Handler(Looper.getMainLooper())
            if (ready) {
                // Posted, never called inline: callers invoke warm() at the
                // top of onCreate with callbacks that touch state built
                // further down.
                main.post(onReady)
                return
            }
            val store = SecretStore(context)
            executor.execute {
                try {
                    store.key()
                } catch (e: Exception) {
                    Log.e(TAG, "Keystore warm-up failed", e)
                }
                ready = true
                main.post(onReady)
            }
        }

        private const val TAG = "agent-relay"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "agent-relay-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BITS = 96
        private const val IV_BYTES = IV_BITS / 8
        private const val TAG_BITS = 128
    }
}
