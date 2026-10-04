package io.github.ealang.pebbletelegramagent

import android.content.Context
import android.util.Base64
import io.github.ealang.pebbletelegramagent.server.WebhookServer
import java.security.SecureRandom

/**
 * Settings. Plain facts live in plain SharedPreferences; the two real
 * secrets — the bearer token and TDLib's database key — live in
 * [SecretStore], encrypted under a Keystore key.
 *
 * The split is deliberate: backups are disabled app-wide and prefs are
 * app-private by uid, so the plain half never leaves the device either.
 * The SecretStore layer is for the copies that ignore `allowBackup` —
 * device-to-device migration, OEM cloud "transfer" tools — where ciphertext
 * whose key is stranded in this phone's TEE is worthless.
 *
 * The token is generated on first read rather than left blank: a server
 * whose auth is "" until someone fills it in is a server that ships
 * unauthenticated.
 */
class Config(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pebble-telegram-agent", Context.MODE_PRIVATE)

    private val secrets = SecretStore(context)

    /** The webhook listener's port, bound at service start; changing it restarts the service. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, WebhookServer.PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = secrets.get(KEY_TOKEN)
            ?: generateToken().also { secrets.put(KEY_TOKEN, it) }
        set(value) = secrets.put(KEY_TOKEN, value)

    fun regenerateToken(): String = generateToken().also { token = it }

    /** Whether the user wants the server up — also what the boot receiver checks. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The one destination chat, chosen from the picker. */
    var telegramChatId: Long?
        get() = prefs.getLong(KEY_TG_CHAT, 0L).takeIf { it != 0L }
        set(value) = prefs.edit().putLong(KEY_TG_CHAT, value ?: 0L).apply()

    /**
     * The chosen chat's title, for display only — the id is what sends.
     * Saved at pick time rather than resolved at render time, so the card
     * can name the destination without a TDLib round-trip.
     */
    var telegramChatTitle: String
        get() = prefs.getString(KEY_TG_CHAT_TITLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TG_CHAT_TITLE, value).apply()

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
     * database holds the account's session keys — this is the secret the
     * SecretStore layer exists for: a copied database file is useless
     * without it, and the Keystore-wrapped key cannot leave this phone.
     */
    val telegramDbKey: ByteArray
        get() {
            secrets.get(KEY_TG_DB_KEY)?.let {
                return Base64.decode(it, Base64.NO_WRAP)
            }
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            secrets.put(KEY_TG_DB_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
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
        private const val KEY_TG_CHAT = "telegram_chat_id"
        private const val KEY_TG_CHAT_TITLE = "telegram_chat_title"
        private const val KEY_TG_DB_KEY = "telegram_db_key"
        private const val KEY_TG_SIGNATURE = "telegram_signature"
    }
}
