package com.example.mydevice.util

object Constants {
    const val BASE_URL = "https://mydevices.myweb.net.au/"
    const val SIGNALR_HUB_URL = "https://mydevices.myweb.net.au/device-hub"

    const val DEFAULT_COMPANY_ID = -1
    const val DEFAULT_DEVICE_ID = ""

    const val STATUS_UPDATE_INTERVAL_MINUTES = 15L
    const val EVENT_SYNC_INTERVAL_MINUTES = 10L
    const val FILE_SYNC_INTERVAL_MINUTES = 30L
    const val KIOSK_APPS_REFRESH_INTERVAL_HOURS = 1L

    const val INACTIVITY_TIMEOUT_MINUTES = 5

    const val HTTP_CALL_TIMEOUT_MS = 120_000L
    const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
    const val HTTP_READ_TIMEOUT_MS = 30_000L
    const val HTTP_WRITE_TIMEOUT_MS = 30_000L

    const val SIGNALR_SERVER_TIMEOUT_SECONDS = 30L
    const val SIGNALR_KEEP_ALIVE_SECONDS = 20L

    object EventTypes {
        const val LOGIN = "Login"
        const val LOGOUT = "Logout"
        const val APP_START = "AppStart"
        const val APP_STOP = "AppStop"
        const val BATTERY_LOW = "BatteryLow"
        const val BATTERY_CHARGING = "BatteryCharging"
        const val BATTERY_FULL = "BatteryFull"
        const val DEVICE_BOOT = "DeviceBoot"
    }

    object SignalREvents {
        const val REBOOT = "Reboot"
        const val WIFI_PROFILE = "WifiProfile"
        const val SEND_MESSAGE = "SendMessageToDevice"
        const val REMOTE_ASSISTANCE_OFFER = "RemoteAssistanceOffer"
        const val REMOTE_ASSISTANCE_CANDIDATE = "RemoteAssistanceCandidate"
        const val SEND_XML_COMMAND = "SendXMLCommandTodevice"
    }

    object SignalRMethods {
        const val ADD_DEVICE_ID = "AddDeviceId"
        const val REMOTE_ASSISTANCE_ANSWER = "RemoteAssistanceAnswer"
        const val REMOTE_ASSISTANCE_CANDIDATE = "RemoteAssistanceCandidate"
        const val MESSAGE_RECEIVED = "MessageReceived"
        const val EVENT_RECEIVED = "EventReceived"
    }
}
