package io.github.ealang.pebbletelegramagent.pebble

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import io.github.ealang.pebbletelegramagent.AgentService
import io.github.ealang.pebbletelegramagent.pipeline.Meta
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The PebbleKit 2 ingress — the second front door into the same pipeline the
 * webhook feeds. CoreApp binds to this service when our watchapp opens on the
 * watch and delivers its AppMessages over AIDL; we extract the transcript,
 * put it on the durable queue, and Ack. Ack therefore means exactly what the
 * webhook's 200 means: durably queued, not yet delivered to Telegram.
 *
 * The service is exported with no permission (a PK2 protocol requirement),
 * so it gates in code, failing closed at three layers:
 *
 *  1. The library's app picker is pinned to CoreApp with auto-select off
 *     (see [io.github.ealang.pebbletelegramagent.App]) — the base class
 *     rejects every other caller by package name.
 *  2. [onBind] wraps the library's binder and re-checks the calling uid
 *     itself: the uid must belong to [PebbleContract.COREAPP_PACKAGE] *and*
 *     carry its pinned signing certificate. This holds even if the package
 *     name is squatted by a sideloaded impostor.
 *  3. [onMessageReceived] accepts only our watchapp UUIDs — CoreApp routes
 *     any watchapp that merely *declares* us as its companion, so the UUID
 *     is checked on our side.
 */
class PebbleIngressService : BasePebbleListenerService() {

    // One thread, so messages are enqueued in the order the watch sent them.
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pebble-ingress")
    }.asCoroutineDispatcher()

    override val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun onDestroy() {
        coroutineScope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    // Layer 2: identity is only readable on the Binder thread, at the entry
    // point — inside the suspend callbacks getCallingUid() would return our
    // own uid. So the library's binder is wrapped rather than subclassed.
    override fun onBind(intent: Intent?): IBinder? {
        val inner = UniversalRequestResponse.Stub.asInterface(super.onBind(intent) ?: return null)
        return object : UniversalRequestResponse.Stub() {
            override fun request(data: Bundle, callback: SendDataCallback) {
                if (!callerIsCoreApp(Binder.getCallingUid())) {
                    Log.w(TAG, "Rejected a PK2 request from a caller that is not CoreApp")
                    // An empty bundle is the library's own refusal shape;
                    // CoreApp reads it as Nack. Never throw across Binder.
                    try {
                        callback.onResult(Bundle())
                    } catch (ignored: DeadObjectException) {
                    }
                    return
                }
                // Same-process nested call: the incoming Binder identity stays
                // in effect, so the library's own package check still works.
                inner.request(data, callback)
            }
        }
    }

    private fun callerIsCoreApp(uid: Int): Boolean {
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        if (PebbleContract.COREAPP_PACKAGE !in packages) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Also accepts certs in the package's past signing history, so a
            // CoreApp key rotation does not brick us.
            packageManager.hasSigningCertificate(
                uid,
                PebbleContract.COREAPP_CERT_SHA256,
                PackageManager.CERT_INPUT_SHA256,
            )
        } else {
            legacyCertMatches()
        }
    }

    /** API 26/27 fallback: hash the signer certificates ourselves. */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun legacyCertMatches(): Boolean = try {
        val info = packageManager.getPackageInfo(
            PebbleContract.COREAPP_PACKAGE,
            PackageManager.GET_SIGNATURES,
        )
        val sha256 = MessageDigest.getInstance("SHA-256")
        info.signatures.orEmpty().any { sig ->
            sha256.digest(sig.toByteArray()).contentEquals(PebbleContract.COREAPP_CERT_SHA256)
        }
    } catch (e: Exception) {
        false
    }

    // Layer 3 plus the actual work. Runs on [dispatcher], so the file I/O is
    // off the main thread and strictly ordered.
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID !in PebbleContract.WATCHAPP_UUIDS) {
            Log.w(TAG, "Nacked a message from foreign watchapp $watchappUUID")
            return ReceiveResult.Nack
        }
        val text = extractTranscript(data) ?: run {
            Log.w(TAG, "Nacked a message with no usable transcript")
            return ReceiveResult.Nack
        }
        val queued = AgentService.enqueueExternal(
            this,
            Meta(
                text = text,
                recordedAtMs = 0, // the watch sends no timestamp
                client = CLIENT_NAME,
                receivedAtMs = System.currentTimeMillis(),
            ),
        )
        // The queue is drained by the service's pipeline, so make sure one
        // is up. The webhook toggle is about the listener socket, not about
        // delivery: with the listener off, the service runs just long enough
        // to drain and stops itself. While CoreApp is bound to us the
        // process has foreground importance, so this start is permitted.
        if (queued) {
            AgentService.start(this)
        }
        return if (queued) ReceiveResult.Ack else ReceiveResult.Nack
    }

    companion object {
        private const val TAG = "pebble-telegram-agent"

        /** What this ingress reports as [Meta.client]. */
        const val CLIENT_NAME = "pebble-watch"
    }
}
