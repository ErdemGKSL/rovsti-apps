package dev.rovsti.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeProbesTest {
    private fun bt(id: String, name: String?, sel: Boolean = false) = EndpointInfo(id, "bluetooth", name, sel)

    @Test fun sampleLinesCarryLengthsNotNames() {
        val line = SpikeProbes.sampleLine(3, SpikeProbes.Sample(listOf(bt("7c1d-0002", "Galaxy Buds", true))))
        assertEquals("t=3 bluetooth len=11 tail=0002 sel=true", line)
        assertTrue(!line.contains("Galaxy"))
    }

    @Test fun anEmptyRunIsUnknownNotFail() {
        val v = SpikeProbes.verdicts(listOf(SpikeProbes.Sample(emptyList())), true, -1)
        assertTrue(v.none { it.startsWith("fail") })
        assertTrue(v.first().startsWith("unknown"))
    }

    @Test fun namesAndStabilityAndLatencyPass() {
        val s = SpikeProbes.Sample(listOf(bt("a", "Buds")))
        val v = SpikeProbes.verdicts(listOf(s, s, s), true, 220)
        assertTrue(v.any { it.startsWith("pass: names") })
        assertTrue(v.any { it.startsWith("pass: id stable") })
        assertTrue(v.any { it.startsWith("pass: selected moved") })
    }

    @Test fun anIdThatChangesWhileTheDeviceStaysFails() {
        val a = SpikeProbes.Sample(listOf(bt("a", "Buds")))
        val b = SpikeProbes.Sample(listOf(bt("b", "Buds")))
        assertTrue(SpikeProbes.idStability(listOf(a, b)).startsWith("fail"))
    }

    @Test fun missingNamesWithDeniedPermissionFails() {
        val s = SpikeProbes.Sample(listOf(bt("a", null)))
        assertTrue(SpikeProbes.verdicts(listOf(s), true, 900).any { it.startsWith("fail") })
    }

    @Test fun slowSelectionFails() {
        val s = SpikeProbes.Sample(listOf(bt("a", "Buds")))
        assertTrue(SpikeProbes.verdicts(listOf(s), true, 800).last().startsWith("fail"))
    }

    @Test fun registeredGroupsSoFar() {
        for (probe in listOf("audio", "calls", "sim", "system")) assertTrue(SpikeProbes.isRegistered(probe))
        // Backup runs in Rust (it drives the pickers); Blocking and the rest are not built yet.
        assertTrue(!SpikeProbes.isRegistered("backup"))
        assertTrue(!SpikeProbes.isRegistered("blocking"))
        assertTrue(SpikeProbes.GROUPS.contains("audio") && SpikeProbes.GROUPS.contains("backup"))
    }
}
