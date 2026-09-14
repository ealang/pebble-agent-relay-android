package io.github.ealang.pebbletelegramwebhook

import android.content.Context
import android.util.Base64
import io.github.ealang.pebbletelegramwebhook.server.WebhookServer
import java.security.SecureRandom

/**
 * Settings, in plain SharedPreferences.
 *
 * Plain on purpose: backups are disabled app-wide, so nothing here ever
 * leaves the device, and SharedPreferences are app-private via the per-app
 * UID sandbox. The threats that remains — root, and `run-as` on a debuggable
 * build — are ones no on-device encryption layer survives anyway.
 *
 * The token is generated on first read rather than left blank: a server
 * whose auth is "" until someone fills it in is a server that ships
 * unauthenticated.
 */
class Config(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pebble-telegram-webhook", Context.MODE_PRIVATE)

    /** The webhook listener's port, bound at service start; changing it restarts the service. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, WebhookServer.PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() {
            prefs.getString(KEY_TOKEN, null)?.let { return it }
            return generateToken().also {
                prefs.edit().putString(KEY_TOKEN, it).apply()
            }
        }
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun regenerateToken(): String = generateToken().also { token = it }

    /** Whether the user wants the server up — also what the boot receiver checks. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Telegram application credentials, from my.telegram.org. These identify
     * the *application*, not the user — every install of a distributed
     * Telegram client ships the same pair compiled into the binary, and the
     * official clients' values are public.
     */
    var telegramApiId: Int?
        get() = prefs.getInt(KEY_TG_API_ID, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_TG_API_ID, value ?: 0).apply()

    var telegramApiHash: String?
        get() = prefs.getString(KEY_TG_API_HASH, null)?.ifBlank { null }
        set(value) = prefs.edit().putString(KEY_TG_API_HASH, value?.trim() ?: "").apply()

    /** The one destination chat, entered as a numeric id. */
    var telegramChatId: Long?
        get() = prefs.getLong(KEY_TG_CHAT, 0L).takeIf { it != 0L }
        set(value) = prefs.edit().putLong(KEY_TG_CHAT, value ?: 0L).apply()

    /**
     * Signature line appended to forwarded messages, on its own line. Read
     * at pickup, so an edit applies to already-queued items too. Blank
     * appends nothing.
     */
    var telegramSignature: String
        get() = prefs.getString(KEY_TG_SIGNATURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TG_SIGNATURE, value.trim()).apply()

    /**
     * TDLib's local-database encryption key, random per install. The
     * database holds the account's session keys, so a per-install key at
     * least ties the database file to this app's private storage.
     */
    val telegramDbKey: ByteArray
        get() {
            prefs.getString(KEY_TG_DB_KEY, null)?.let {
                return Base64.decode(it, Base64.NO_WRAP)
            }
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit()
                .putString(KEY_TG_DB_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
                .apply()
            return fresh
        }

    /**
     * Why the last attempt to bring the service up failed, or null.
     * Persisted because the failures worth catching here are the ones that
     * take the process with them.
     */
    var lastStartFailure: String?
        get() = prefs.getString(KEY_START_FAILURE, null)
        set(value) = prefs.edit().putString(KEY_START_FAILURE, value).apply()

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_FAILURE = "last_start_failure"
        private const val KEY_TG_API_ID = "telegram_api_id"
        private const val KEY_TG_API_HASH = "telegram_api_hash"
        private const val KEY_TG_CHAT = "telegram_chat_id"
        private const val KEY_TG_DB_KEY = "telegram_db_key"
        private const val KEY_TG_SIGNATURE = "telegram_signature"
    }
}
