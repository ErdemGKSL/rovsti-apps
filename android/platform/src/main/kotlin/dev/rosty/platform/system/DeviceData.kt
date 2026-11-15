package dev.rosty.platform.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File

/**
 * The connection the phone is on, for a rule that decides what may be downloaded by itself. When more than one
 * holds, the most restrictive is named: Data Saver before roaming before mobile data.
 */
enum class DataNetwork {
    /** Not metered: Wi-Fi, Ethernet. */
    Wifi,

    /** Metered, on the home network. */
    Mobile,

    Roaming,

    /** Data Saver is on for this app on a metered network. */
    DataSaver,

    /** No network that reaches the internet. */
    None,
    ;

    companion object {
        /** The kind of a connection from what the system says about it. */
        fun of(
            connected: Boolean,
            metered: Boolean,
            roaming: Boolean,
            restricted: Boolean,
        ): DataNetwork =
            when {
                !connected -> None
                !metered -> Wifi
                restricted -> DataSaver
                roaming -> Roaming
                else -> Mobile
            }
    }
}

/** What a download rule needs to know about the phone: the connection and the room left. Tests use a fake. */
interface DeviceData {
    fun network(): DataNetwork

    /** Bytes free on the volume of [dir]; null when it cannot be told. */
    fun freeBytes(dir: File): Long?
}

/**
 * [DeviceData] from `ConnectivityManager` and `StatFs`. Never throws: an app that does not hold
 * `ACCESS_NETWORK_STATE` (the app's manifest declares it, not this library's) reads as no network.
 */
class AndroidDeviceData(
    context: Context,
) : DeviceData {
    private val app = context.applicationContext

    override fun network(): DataNetwork {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return DataNetwork.None
        return try {
            val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            DataNetwork.of(
                connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                metered = manager.isActiveNetworkMetered,
                roaming = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
                restricted =
                    manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            )
        } catch (_: SecurityException) {
            DataNetwork.None
        }
    }

    override fun freeBytes(dir: File): Long? =
        try {
            var at: File? = dir
            while (at != null && !at.exists()) at = at.parentFile
            at?.let { StatFs(it.path).availableBytes }
        } catch (_: IllegalArgumentException) {
            null
        }
}
