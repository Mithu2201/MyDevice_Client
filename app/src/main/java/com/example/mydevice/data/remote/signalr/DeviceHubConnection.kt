package com.example.mydevice.data.remote.signalr

import android.os.SystemClock
import android.util.Log
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.util.Constants
import com.microsoft.signalr.Action
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import com.microsoft.signalr.TransportEnum
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Manages the persistent SignalR WebSocket to /device-hub.
 *
 * LIFECYCLE:
 * 1. connect() builds the HubConnection, optionally with auth token, registers listeners
 * 2. On successful connect, sends AddDeviceId to register with the hub
 * 3. Server pushes commands (Reboot, WifiProfile, SendMessage, etc.)
 * 4. On disconnect, retries with exponential backoff [0s, 10s, 20s, 30s, 60s]
 * 5. After all retries exhausted, waits for internet and reconnects
 * 6. If 5+ minutes since last reconnect attempt, retry counter resets to 0
 *
 * EVENTS are exposed as SharedFlows so ViewModels/Services can collect them.
 */
class DeviceHubConnection(
    private val securePreferences: SecurePreferences
) {
    private var hubConnection: HubConnection? = null
    private var deviceId: String = ""
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Monotonic id so stale [start]/[send] work is ignored after a newer [connect]. */
    private var connectionSession: Long = 0L

    /** Single in-flight start+send; cancelled on each [connect] to stop overlapping hub.start() races. */
    private var connectionWork: Job? = null

    private val retryDelays = longArrayOf(0, 10_000, 20_000, 30_000, 60_000)
    private var retryIndex = 0
    private var lastReconnectTime = 0L

    // ── Connection state observable by UI ───────────────────────────────────

    private val _connectionState = MutableStateFlow(HubConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    // ── Incoming command flows (ViewModels collect these) ───────────────────

    /** Seconds until reboot (server `/SendRebootCall`); parsed from int/double/object or 0. */
    private val _rebootCommand = MutableSharedFlow<Double>(extraBufferCapacity = 16)
    val rebootCommand = _rebootCommand.asSharedFlow()

    private var lastRebootSignalMs = 0L

    private val _wifiProfileCommand = MutableSharedFlow<WifiProfilePayload>(extraBufferCapacity = 8)
    val wifiProfileCommand = _wifiProfileCommand.asSharedFlow()

    private val _messageCommand = MutableSharedFlow<SignalRMessage>()
    val messageCommand = _messageCommand.asSharedFlow()

    private val _remoteAssistanceOffer = MutableSharedFlow<String>()
    val remoteAssistanceOffer = _remoteAssistanceOffer.asSharedFlow()

    private val _remoteAssistanceCandidate = MutableSharedFlow<String>()
    val remoteAssistanceCandidate = _remoteAssistanceCandidate.asSharedFlow()

    private val _xmlCommand = MutableSharedFlow<String>()
    val xmlCommand = _xmlCommand.asSharedFlow()

    // ── Connect ─────────────────────────────────────────────────────────────

    fun connect(registrationId: String) {
        val trimmed = registrationId.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "connect() ignored: empty registration id (admin reboot uses /api/.../deviceId=122 — must match AddDeviceId)")
            return
        }
        // Avoid orphan WebSockets: old connection must stop before a new HubConnection is built.
        if (hubConnection?.connectionState == HubConnectionState.CONNECTED && deviceId == trimmed) {
            Log.i(TAG, "SignalR already connected; AddDeviceId already sent as $trimmed — skip reconnect")
            return
        }
        connectionWork?.cancel()
        disconnect()
        this.deviceId = trimmed
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        val token = securePreferences.accessToken
        Log.i(
            TAG,
            "Preparing SignalR connection for registrationId=$trimmed, hasToken=${!token.isNullOrBlank()}"
        )

        val builder = HubConnectionBuilder.create(Constants.SIGNALR_HUB_URL)
            .withTransport(TransportEnum.WEBSOCKETS)
            .shouldSkipNegotiate(true)

        if (!token.isNullOrBlank()) {
            Log.i(TAG, "Connecting to SignalR with bearer token")
            builder.withAccessTokenProvider(io.reactivex.rxjava3.core.Single.just(token))
        } else {
            Log.i(TAG, "Connecting to SignalR without token (anonymous hub mode)")
        }

        hubConnection = builder.build().apply {
            serverTimeout = TimeUnit.SECONDS.toMillis(Constants.SIGNALR_SERVER_TIMEOUT_SECONDS)
            keepAliveInterval = TimeUnit.SECONDS.toMillis(Constants.SIGNALR_KEEP_ALIVE_SECONDS)
        }

        registerListeners()
        val session = connectionSession
        connectionWork = scope.launch {
            runConnectionStart(session)
        }
    }

    // ── Register all incoming event listeners ───────────────────────────────

    /**
     * Legacy mydevicesandroid registers a single `Double` handler; .NET often sends JSON numbers as int.
     * Use `Object` for one-arg + `Action` for zero-arg (matches hub `SendAsync("Reboot")` / `SendAsync("Reboot", seconds)`).
     */
    private fun registerRebootHandlers(hub: HubConnection) {
        val event = Constants.SignalREvents.REBOOT

        fun parseArg(arg: Any?): Double {
            if (arg == null) return 0.0
            return when (arg) {
                is Number -> arg.toDouble()
                is String -> arg.trim().toDoubleOrNull() ?: 0.0
                else -> {
                    Log.w(TAG, "Reboot: unexpected payload type ${arg.javaClass.name} value=$arg")
                    0.0
                }
            }
        }

        fun emitDebounced(seconds: Double, source: String) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebootSignalMs < 3_000L) {
                Log.w(TAG, "Reboot: ignored duplicate ($source) within 3s")
                return
            }
            lastRebootSignalMs = now
            Log.i(TAG, "Reboot: received ($source) delaySeconds=$seconds (filter logcat: $TAG)")
            scope.launch { _rebootCommand.emit(seconds) }
        }

        hub.on(
            event,
            Action {
                emitDebounced(0.0, "no-args")
            }
        )

        hub.on(
            event,
            { arg: Any? ->
                emitDebounced(parseArg(arg), "one-arg:${arg?.javaClass?.simpleName}")
            },
            Object::class.java
        )

        // Same as legacy: `connection.on("Reboot", this::handleRebootEvent, Double.class)`
        hub.on(event, { v: Double? ->
            emitDebounced(v ?: 0.0, "Double")
        }, Double::class.java)
    }

    private fun registerListeners() {
        val hub = hubConnection ?: return

        registerRebootHandlers(hub)

        hub.on(
            Constants.SignalREvents.WIFI_PROFILE,
            { payload ->
                scope.launch {
                    Log.i(TAG, "SignalR WifiProfile received: $payload")
                    _wifiProfileCommand.emit(payload)
                }
            },
            WifiProfilePayload::class.java
        )

        hub.on(Constants.SignalREvents.SEND_MESSAGE, { payload ->
            scope.launch {
                Log.i(TAG, "SignalR message received: id=${payload.id}, from=${payload.sendBy}")
                _messageCommand.emit(payload)
                hub.send(Constants.SignalRMethods.MESSAGE_RECEIVED, payload.id)
                Log.i(TAG, "SignalR message ack sent: id=${payload.id}")
            }
        }, SignalRMessage::class.java)

        hub.on(Constants.SignalREvents.REMOTE_ASSISTANCE_OFFER, { sdp ->
            scope.launch { _remoteAssistanceOffer.emit(sdp) }
        }, String::class.java)

        hub.on(Constants.SignalREvents.REMOTE_ASSISTANCE_CANDIDATE, { candidate ->
            scope.launch { _remoteAssistanceCandidate.emit(candidate) }
        }, String::class.java)

        hub.on(Constants.SignalREvents.SEND_XML_COMMAND, { xml ->
            scope.launch {
                _xmlCommand.emit(xml)
                hub.send(Constants.SignalRMethods.EVENT_RECEIVED, deviceId)
            }
        }, String::class.java)

        hub.onClosed { error ->
            _connectionState.value = HubConnectionState.DISCONNECTED
            Log.w(TAG, "SignalR disconnected: ${error?.message}")
            scope.launch { reconnectWithBackoff() }
        }
    }

    // ── Start the actual connection ─────────────────────────────────────────

    /**
     * Backend routes `SendRebootCall?deviceId=122` to the SignalR connection that called
     * `AddDeviceId` with **122**. Prefer sending an [Int] (matches MDM doc / admin API).
     */
    private fun sendAddDeviceId(hub: HubConnection) {
        val raw = deviceId.trim()
        val asInt = raw.toIntOrNull()
        if (asInt != null) {
            hub.send(Constants.SignalRMethods.ADD_DEVICE_ID, asInt)
            Log.i(
                TAG,
                "AddDeviceId sent as INTEGER $asInt — admin reboot API must use the same deviceId"
            )
        } else {
            hub.send(Constants.SignalRMethods.ADD_DEVICE_ID, raw)
            Log.i(
                TAG,
                "AddDeviceId sent as STRING (non-numeric); ensure backend maps this to the same key as SendRebootCall"
            )
        }
    }

    /**
     * Must not overlap: parallel [hub.start] + [send] causes
     * "The 'send' method cannot be called if the connection is not active."
     */
    private suspend fun runConnectionStart(session: Long) {
        val hub = hubConnection ?: run {
            Log.w(TAG, "runConnectionStart: hub is null (cancelled or replaced)")
            return
        }
        try {
            hub.start().blockingAwait()
            if (session != connectionSession || hub !== hubConnection) {
                Log.w(TAG, "runConnectionStart: stale session (replaced by newer connect), skipping AddDeviceId")
                return
            }
            // Rare race: internal state lags behind blockingAwait completion on some devices.
            if (hub.connectionState != HubConnectionState.CONNECTED) {
                delay(50)
            }
            if (hub.connectionState != HubConnectionState.CONNECTED) {
                throw IllegalStateException(
                    "Hub not CONNECTED after start (state=${hub.connectionState}) — cannot send AddDeviceId"
                )
            }
            _connectionState.value = HubConnectionState.CONNECTED
            retryIndex = 0
            sendAddDeviceId(hub)
            Log.i(TAG, "SignalR ready: AddDeviceId completed for session=$session")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SignalR connect failed: ${e.message}", e)
            _connectionState.value = HubConnectionState.DISCONNECTED
            if (session == connectionSession) {
                reconnectWithBackoff()
            }
        }
    }

    private fun startConnection() {
        val session = connectionSession
        connectionWork?.cancel()
        connectionWork = scope.launch {
            runConnectionStart(session)
        }
    }

    // ── Reconnect with exponential backoff ──────────────────────────────────

    private suspend fun reconnectWithBackoff() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectTime > 5 * 60 * 1000) {
            retryIndex = 0
        }
        lastReconnectTime = now

        if (retryIndex < retryDelays.size) {
            val delayMs = retryDelays[retryIndex]
            retryIndex++
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $retryIndex/${retryDelays.size})")
            delay(delayMs)
            startConnection()
        } else {
            Log.w(
                TAG,
                "Fast retries exhausted; retrying in ${Constants.SIGNALR_LONG_RETRY_MS}ms (persistent)"
            )
            delay(Constants.SIGNALR_LONG_RETRY_MS)
            retryIndex = 0
            startConnection()
        }
    }

    // ── Outbound methods (app → server) ─────────────────────────────────────

    fun sendRemoteAssistanceAnswer(sdp: String) {
        hubConnection?.send(Constants.SignalRMethods.REMOTE_ASSISTANCE_ANSWER, sdp)
    }

    fun sendRemoteAssistanceCandidate(candidate: String) {
        hubConnection?.send(Constants.SignalRMethods.REMOTE_ASSISTANCE_CANDIDATE, candidate)
    }

    // ── Disconnect ──────────────────────────────────────────────────────────

    fun disconnect() {
        connectionWork?.cancel()
        connectionWork = null
        connectionSession++
        // Do not cancel the shared scope here. MainActivity refreshes the hub by
        // calling disconnect() followed by connect(), and canceling the scope
        // permanently breaks future reconnect attempts and incoming event emits.
        try {
            hubConnection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "SignalR stop failed", e)
        }
        hubConnection = null
        _connectionState.value = HubConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean =
        hubConnection?.connectionState == HubConnectionState.CONNECTED

    companion object {
        /** Match legacy mydevicesandroid log filter: `adb logcat | findstr SignalR:DeviceHub` */
        private const val TAG = "SignalR:DeviceHubConnection"
    }
}

/**
 * Data class representing a message pushed from the server via SignalR.
 */
data class SignalRMessage(
    val id: Int = 0,
    val mobileStatusId: Int = 0,
    val sendBy: String = "",
    val description: String = "",
    val isSent: Boolean = false,
    val sentAt: String = "",
    val receivedAt: String = ""
)
