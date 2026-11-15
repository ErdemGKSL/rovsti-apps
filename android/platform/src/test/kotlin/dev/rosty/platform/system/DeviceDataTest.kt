package dev.rosty.platform.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DeviceDataTest {
    private fun kind(
        connected: Boolean = true,
        metered: Boolean = false,
        roaming: Boolean = false,
        restricted: Boolean = false,
    ) = DataNetwork.of(connected, metered, roaming, restricted)

    @Test fun noConnectionIsNoneWhateverElseIsSaid() {
        assertEquals(DataNetwork.None, kind(connected = false, metered = true, roaming = true, restricted = true))
    }

    @Test fun anUnmeteredNetworkIsWifiAlsoWithDataSaverOn() {
        assertEquals(DataNetwork.Wifi, kind())
        // Data Saver only limits metered networks.
        assertEquals(DataNetwork.Wifi, kind(restricted = true))
    }

    @Test fun aMeteredNetworkNamesTheMostRestrictiveKind() {
        assertEquals(DataNetwork.Mobile, kind(metered = true))
        assertEquals(DataNetwork.Roaming, kind(metered = true, roaming = true))
        assertEquals(DataNetwork.DataSaver, kind(metered = true, restricted = true))
        assertEquals(DataNetwork.DataSaver, kind(metered = true, roaming = true, restricted = true))
    }

    @Test fun theFakeAnswersWhatItWasSet() {
        val fake = FakeDeviceData(DataNetwork.Mobile, free = 42)
        assertEquals(DataNetwork.Mobile, fake.network())
        assertEquals(42L, fake.freeBytes(File("/nowhere")))
        fake.kind = DataNetwork.None
        fake.free = null
        assertEquals(DataNetwork.None, fake.network())
        assertNull(fake.freeBytes(File("/nowhere")))
    }
}
