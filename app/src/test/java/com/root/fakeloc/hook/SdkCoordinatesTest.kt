package com.root.fakeloc.hook

import org.junit.Assert.*
import org.junit.Test

class SdkCoordinatesTest {
    @Test fun coordinatesUseTheRequestedSdkSystem() {
        val wgs = SdkCoordinates.convert(39.9087, 116.3975, "wgs84")!!
        val gcj = SdkCoordinates.convert(wgs.lat, wgs.lng, "gcj02")!!
        val bd = SdkCoordinates.convert(wgs.lat, wgs.lng, "bd09ll")!!
        assertEquals(39.910103, gcj.lat, .000002)
        assertEquals(116.403744, gcj.lng, .000002)
        assertTrue(bd.lat > gcj.lat + .005)
        assertTrue(bd.lng > gcj.lng + .005)
        assertNull(SdkCoordinates.convert(wgs.lat, wgs.lng, "bd09"))
    }
    @Test fun outsideChinaKeepsWgsCoordinatesForGcj() {
        assertEquals(SdkCoordinates.Point(40.7128, -74.006), SdkCoordinates.convert(40.7128, -74.006, "gcj02"))
    }
}
