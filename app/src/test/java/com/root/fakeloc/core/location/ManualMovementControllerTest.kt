package com.root.fakeloc.core.location

import org.junit.Assert.*
import org.junit.Test

class ManualMovementControllerTest {
    @Test fun movingNorthUsesConfiguredSpeedAndReleaseHoldsPosition() {
        val control = ManualMovementController()
        val start = GeoPoint(26.0, 117.0)
        control.update(0.0, -1.0, start, 1000)
        val moving = control.nextPosition(500, 36f, 1500)
        assertEquals(5.0, RouteCalculator.calculateDistance(start, moving.first), 0.01)
        assertTrue(moving.first.latitude > start.latitude)
        control.update(0.0, 0.0, moving.first, 1500)
        val held = control.nextPosition(1000, 36f, 2500)
        assertEquals(moving.first, held.first)
        assertEquals(0f, held.second, 0f)
        assertTrue(control.active)
    }

    @Test fun staleJoystickCannotKeepMovingAfterWindowCloses() {
        val control = ManualMovementController()
        val start = GeoPoint(0.0, 179.99999)
        control.update(1.0, 0.0, start, 1000)
        val moved = control.nextPosition(500, 36f, 1500)
        assertTrue(moved.first.longitude < 0)
        val stale = control.nextPosition(500, 36f, 1800)
        assertEquals(moved.first, stale.first)
        assertEquals(0f, stale.second, 0f)
    }

    @Test fun diagonalInputIsBoundedAndInvalidInputStops() {
        val control = ManualMovementController()
        val start = GeoPoint(89.9999, 0.0)
        control.update(10.0, -10.0, start, 1000)
        val moved = control.nextPosition(500, 36f, 1500)
        assertEquals(5.0, RouteCalculator.calculateDistance(start, moved.first), 0.01)
        assertTrue(moved.first.latitude in -90.0..90.0)
        control.update(Double.NaN, Double.POSITIVE_INFINITY, moved.first, 1500)
        assertEquals(0f, control.nextPosition(100, 36f, 1600).second, 0f)
    }

    @Test fun resumingRouteConnectsFromManualPointWithoutTeleporting() {
        val start = GeoPoint(26.0, 117.0)
        val config = MockConfig(mode = MockMode.ROUTE_TRACK, speedKmh = 36f, isLoop = false,
            waypoints = mutableListOf(RouteWaypoint("a", start, 0f), RouteWaypoint("b", GeoPoint(26.01, 117.0), 0f)))
        val route = RouteCalculator(config)
        val paused = route.nextPosition(1000).first
        val manual = ManualMovementController.offset(paused, 20.0, 90.0)
        route.resumeFrom(manual)
        assertEquals(manual, route.nextPosition(0).first)
        val connecting = route.nextPosition(1000).first
        assertEquals(10.0, RouteCalculator.calculateDistance(manual, connecting), 0.02)
        assertEquals(10.0, RouteCalculator.calculateDistance(connecting, paused), 0.02)
        val continued = route.nextPosition(2000).first
        assertTrue(continued.latitude > paused.latitude)
    }
}
