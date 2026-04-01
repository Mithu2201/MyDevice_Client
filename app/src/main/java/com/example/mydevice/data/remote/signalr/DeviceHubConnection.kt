package com.example.mydevice.data.remote.signalr

import android.util.Log
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.util.Constants
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

    private val retryDelays = longArrayOf(0, 10_000, 20_000, 30_000, 60_000)
    private var retryIndex = 0
    private var lastReconnectTime = 0L

    // ── Connection state observable by UI ───────────────────────────────────

    private val _connectionState = MutableStateFlow(HubConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    // ── Incoming command flows (ViewModels collect these) ───────────────────

    private val _rebootCommand = MutableSharedFlow<Unit>()
    val rebootCommand = _rebootCommand.asSharedFlow()

    private val _wifiProfileCommand = MutableSharedFlow<String>()
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

    fun connect(deviceId: String) {
        this.deviceId = deviceId
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        val token = securePreferences.accessToken

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
        startConnection()
    }

    // ── Register all incoming event listeners ───────────────────────────────

    private fun registerListeners() {
        val hub = hubConnection ?: return

        hub.on(Constants.SignalREvents.REBOOT, {
            scope.launch { _rebootCommand.emit(Unit) }
        }, Void::class.java)

        hub.on(Constants.SignalREvents.WIFI_PROFILE, { payload ->
            scope.launch { _wifiProfileCommand.emit(payload) }
        }, String::class.java)

        hub.on(Constants.SignalREvents.SEND_MESSAGE, { payload ->
            scope.launch {
                _messageCommand.emit(payload)
                hub.send(Constants.SignalRMethods.MESSAGE_RECEIVED, payload.id)
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

    private fun startConnection() {
        scope.launch {
            try {
                hubConnection?.start()?.blockingAwait()
                _connectionState.value = HubConnectionState.CONNECTED
                retryIndex = 0
                hubConnection?.send(Constants.SignalRMethods.ADD_DEVICE_ID, deviceId)
                Log.i(TAG, "SignalR connected, registered device: $deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "SignalR connect failed: ${e.message}")
                _connectionState.value = HubConnectionState.DISCONNECTED
                reconnectWithBackoff()
            }
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
            val delay = retryDelays[retryIndex]
            retryIndex++
            Log.i(TAG, "Reconnecting in ${delay}ms (attempt $retryIndex/${retryDelays.size})")
            delay(delay)
            startConnection()
        } else {
            Log.w(TAG, "All retries exhausted, waiting for network change to reconnect")
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
        private const val TAG = "DeviceHubConnection"
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
