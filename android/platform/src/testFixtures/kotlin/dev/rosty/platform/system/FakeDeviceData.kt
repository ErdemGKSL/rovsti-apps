package dev.rosty.platform.system

import java.io.File

/** A [DeviceData] a test sets by hand. */
class FakeDeviceData(
    @Volatile var kind: DataNetwork = DataNetwork.Wifi,
    @Volatile var free: Long? = null,
) : DeviceData {
    override fun network(): DataNetwork = kind

    override fun freeBytes(dir: File): Long? = free
}
