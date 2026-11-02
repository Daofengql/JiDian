package com.root.fakeloc.core.sensor

import com.root.fakeloc.core.location.CadenceMode
import com.root.fakeloc.core.location.MockConfig
import org.junit.Assert.*
import org.junit.Test

class StepSimulatorTest {
    @Test fun countsAllStepsBetweenLocationUpdates() {
        val config = MockConfig(cadenceMode = CadenceMode.CUSTOM_FIXED, targetCadenceSpm = 180, enableCadenceJitter = false)
        val engine = StepSimulator(config)
        repeat(60) { engine.tick(3f, 1000) }
        assertEquals(180L, engine.getCumulativeSteps())
        config.targetCadenceSpm = 120
        engine.tick(3f, 1000)
        assertEquals(182L, engine.getCumulativeSteps())
    }

    @Test fun stationaryDoesNotAccumulateSteps() {
        val engine = StepSimulator(MockConfig())
        engine.tick(0f, 60000)
        assertEquals(0L, engine.getCumulativeSteps())
        assertEquals(0f, engine.currentSpm, 0f)
    }
}
