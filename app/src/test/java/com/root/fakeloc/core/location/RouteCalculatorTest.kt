package com.root.fakeloc.core.location

import org.junit.Assert.*
import org.junit.Test

class RouteCalculatorTest {
    private fun config(loop: Boolean = true) = MockConfig(
        mode = MockMode.ROUTE_TRACK,
        speedKmh = 36f,
        isLoop = loop,
        waypoints = mutableListOf(
            RouteWaypoint("a", GeoPoint(0.0, 0.0), 0f),
            RouteWaypoint("b", GeoPoint(0.0, 0.001), 0f),
            RouteWaypoint("c", GeoPoint(0.0, 0.002), 0f)
        )
    )

    @Test fun routeReversesFromTheLastPointWithoutTeleporting() {
        val calculator = RouteCalculator(config())
        val end = calculator.nextPosition(22000).first
        val reverse = calculator.nextPosition(2000).first
        assertTrue(end.longitude > 0.0019)
        assertTrue(reverse.longitude > 0.0017)
        assertTrue(reverse.longitude < end.longitude)
    }

    @Test fun singlePassStopsAtTheLastPoint() {
        val calculator = RouteCalculator(config(false))
        calculator.nextPosition(30000)
        val (point, speed) = calculator.nextPosition(1000)
        assertEquals(0.002, point.longitude, 0.0000001)
        assertEquals(0f, speed, 0f)
    }

    @Test fun speedUpdatesPreserveRouteProgressAndZeroPauses() {
        val config = config()
        val calculator = RouteCalculator(config)
        val first = calculator.nextPosition(2000).first
        config.speedKmh = 0f
        assertEquals(first, calculator.nextPosition(5000).first)
        config.speedKmh = 72f
        val later = calculator.nextPosition(1000).first
        assertEquals(40.0, RouteCalculator.calculateDistance(GeoPoint(0.0, 0.0), later), 0.01)
    }

    @Test fun duplicatePointsAndEmptyRoutesAreSafe() {
        val config = config()
        config.waypoints[1] = config.waypoints[0].copy(id = "b")
        assertTrue(RouteCalculator(config).nextPosition(1000).first.longitude > 0)
        config.waypoints.clear()
        assertEquals(config.targetPoint, RouteCalculator(config).nextPosition(1000).first)
    }

    @Test fun interpolationCrossesTheAntimeridianUsingTheShortArc() {
        val middle = RouteCalculator.interpolate(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0), 0.5)
        assertEquals(180.0, kotlin.math.abs(middle.longitude), 0.00001)
    }
}
