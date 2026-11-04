package com.recodate.drive.data

import com.recodate.drive.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FallbackCancellationTest {
    private val point = DemoData.demoCarLocation

    @Test fun cancelledPlaceSearchNeverCallsDemo() = runTest {
        var fallbackCalled = false
        val source = FallbackPlaceSource(object : PlaceSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>): List<Place> =
                throw CancellationException("replaced request")
        }, object : PlaceSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>): List<Place> {
                fallbackCalled = true
                return emptyList()
            }
        })
        try {
            source.nearby(point, 1000, emptySet())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(fallbackCalled)
    }

    @Test fun cancelledParkingSearchNeverCallsDemo() = runTest {
        var fallbackCalled = false
        val source = FallbackParkingSource(object : ParkingSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> = throw CancellationException()
        }, object : ParkingSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> {
                fallbackCalled = true
                return emptyList()
            }
        })
        try {
            source.nearby(point, 1000)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(fallbackCalled)
    }

    @Test fun networkFailureStillUsesFallback() = runTest {
        var fallbackCalled = false
        val source = FallbackParkingSource(object : ParkingSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> = throw IOException("offline")
        }, object : ParkingSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> {
                fallbackCalled = true
                return emptyList()
            }
        })
        assertTrue(source.nearby(point, 1000).isEmpty())
        assertTrue(fallbackCalled)
    }
}
