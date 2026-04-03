package com.example.mydevice.data.remote.signalr

/**
 * Hub event **WifiProfile** payload — mirrors
 * `au.com.softclient.mydevices.models.response.WifiProfileResponse` (essid / password / encryption).
 *
 * Must use mutable JVM fields so the Microsoft SignalR Java client can deserialize JSON into this type.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class WifiProfilePayload {
    @JvmField
    var essid: String? = null

    /** Some serializers use uppercase (defensive). */
    @JvmField
    var ESSID: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var encryption: String? = null

    /** Admin UI “Active”; if false, skip applying (optional — server may omit). */
    @JvmField
    var active: Boolean? = null

    fun resolvedEssid(): String = (essid ?: ESSID)?.trim().orEmpty()

    override fun toString(): String =
        "WifiProfilePayload(essid=${resolvedEssid()}, encryption=$encryption, active=$active)"
}
