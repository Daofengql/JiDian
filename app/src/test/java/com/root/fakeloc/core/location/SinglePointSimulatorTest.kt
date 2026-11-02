package com.root.fakeloc.core.location

import java.util.Random
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SinglePointSimulatorTest {
    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    @Test fun disabledJitterKeepsTheExactSelectedPoint() {
        val config = MockConfig(targetPoint = GeoPoint(-33.9087654, 151.1234567))
        val simulator = SinglePointSimulator(config, Random(7))
        repeat(100) { assertEquals(config.targetPoint, simulator.nextPoint()) }
    }

    @Test fun jitterStaysWithinRadiusWithoutAccumulatingDrift() {
        for (target in listOf(GeoPoint(39.9087, 116.3975), GeoPoint(89.9999, 179.999999), GeoPoint(-89.9999, -179.999999))) {
            val config = MockConfig(targetPoint = target, enableLocationJitter = true, locationJitterMeters = 3.0)
            val simulator = SinglePointSimulator(config, Random(42))
            repeat(1000) {
                val point = simulator.nextPoint()
                assertTrue(point.latitude in -90.0..90.0)
                assertTrue(point.longitude in -180.0..180.0)
                assertTrue(distance(target, point) <= 3.01)
            }
        }
    }

    @Test fun turningJitterOffImmediatelyReturnsToTheTarget() {
        val config = MockConfig(enableLocationJitter = true)
        val simulator = SinglePointSimulator(config, Random(1))
        assertNotEquals(config.targetPoint, simulator.nextPoint())
        config.enableLocationJitter = false
        assertEquals(config.targetPoint, simulator.nextPoint())
        config.targetPoint = GeoPoint(0.0, -180.0)
        assertEquals(config.targetPoint, simulator.nextPoint())
    }

    @Test fun liveUpdatesCarryPointOptionsAndKeepRoutePreferences() {
        val config = MockConfig(speedKmh = 10f, targetCadenceSpm = 180)
        val updated = config.copy(enableLocationJitter = true, locationJitterMeters = 2.5,
            enableAltitudeSimulation = true, altitudeMeters = -12.5, enableCellSync = true)
        config.applyFrom(updated)
        assertTrue(config.enableLocationJitter)
        assertEquals(2.5, config.locationJitterMeters, 0.0)
        assertTrue(config.enableAltitudeSimulation)
        assertEquals(-12.5, config.altitudeMeters, 0.0)
        assertTrue(config.enableCellSync)
        config.applyFrom(config.copy(mode = MockMode.ROUTE_TRACK))
        assertEquals(10f, config.speedKmh, 0f)
        assertEquals(180, config.targetCadenceSpm)
        assertTrue(config.enableLocationJitter)
    }
}
