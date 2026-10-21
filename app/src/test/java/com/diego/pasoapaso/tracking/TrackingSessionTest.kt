package com.diego.pasoapaso.tracking

import org.junit.Assert.*
import org.junit.Test

class TrackingSessionTest {
    private fun fix(latitude: Double = -12.0, time: Long = 1000, accuracy: Float = 5f) =
        GpsFix(latitude, -77.0, accuracy, 1_700_000_000_000L + time, time)

    @Test fun recoveryIsPausedAndDoesNotCountDowntime() {
        val session=TrackingSession()
        session.restore(listOf(RoutePoint(fix(),2)),50_000,250.0,30_000)
        assertEquals(SessionState.PAUSED,session.state)
        assertEquals(30_000L,session.durationMs(9_000_000))
        assertFalse(session.accept(fix(-11.0,1000),1000))
        session.start(10_000,60_000)
        assertTrue(session.accept(fix(-11.0,10_000),10_000))
        assertEquals(250.0,session.distanceMeters,0.001)
        assertEquals(3,session.points.last().segment)
        assertEquals(50_000L,session.startedAt)
    }
    @Test(expected=IllegalArgumentException::class) fun recoveryRejectsInvalidCoordinates() {
        TrackingSession().restore(listOf(RoutePoint(fix(100.0),0)),1,0.0,0)
    }
    @Test(expected=IllegalArgumentException::class) fun recoveryCannotOverwriteActiveSession() {
        val session=TrackingSession();session.start(1000,1000)
        session.restore(emptyList(),1000,0.0,0)
    }

    @Test fun firstPointDoesNotAddDistance() {
        val session = TrackingSession()
        session.start(1000, 50_000)
        assertTrue(session.accept(fix(), 1000))
        assertEquals(0.0, session.distanceMeters, 0.001)
        assertEquals(50_000L, session.startedAt)
    }

    @Test fun walkingAddsGeographicDistance() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        assertTrue(session.accept(fix(-11.9999, 11_000), 11_000))
        assertEquals(11.12, session.distanceMeters, 0.1)
    }

    @Test fun stationaryNoiseDoesNotAccumulate() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        repeat(60) { index ->
            val time = 4000L + index * 3000
            assertFalse(session.accept(fix(-12.0 + if (index % 2 == 0) 0.00001 else -0.00001, time), time))
        }
        assertEquals(0.0, session.distanceMeters, 0.001)
        assertEquals(1, session.pointCount)
    }

    @Test fun invalidInaccurateStaleAndPreSessionPointsAreRejected() {
        val session = TrackingSession()
        session.start(1000, 0)
        assertFalse(session.accept(fix(time = 999), 1000))
        assertFalse(session.accept(fix(accuracy = 70f), 1000))
        assertFalse(session.accept(fix(), 20_000))
        assertFalse(session.accept(fix(Double.NaN), 1000))
        assertFalse(session.accept(fix(95.0), 1000))
        assertFalse(session.accept(fix(accuracy = 0f), 1000))
        assertFalse(session.accept(fix(time = 2000), 1000))
        assertEquals(0, session.pointCount)
    }

    @Test fun jumpsAndOutOfOrderPointsDoNotInflateDistance() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        assertFalse(session.accept(fix(-11.99, 4000), 4000))
        assertTrue(session.accept(fix(-11.9999, 11_000), 11_000))
        assertFalse(session.accept(fix(-11.9998, 9000), 12_000))
        assertEquals(11.12, session.distanceMeters, 0.1)
    }

    @Test fun resumeDoesNotConnectAcrossPauseAndExcludesPausedTime() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        session.accept(fix(-11.9999, 11_000), 11_000)
        session.pause(12_000)
        assertFalse(session.accept(fix(-11.9998, 20_000), 20_000))
        session.start(100_000, 100_000)
        assertTrue(session.accept(fix(-11.9, 100_000), 100_000))
        assertEquals(11.12, session.distanceMeters, 0.1)
        assertEquals(listOf(0, 0, 1), session.points.map { it.segment })
        assertEquals(16_000L, session.durationMs(105_000))
    }

    @Test fun longSignalLossStartsAnotherSegment() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        assertTrue(session.accept(fix(-11.9, 40_000), 40_000))
        assertEquals(0.0, session.distanceMeters, 0.001)
        assertEquals(listOf(0, 1), session.points.map { it.segment })
    }

    @Test fun slowWalkingAfterStationaryPeriodKeepsContinuousSegment() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(fix(), 1000)
        for (time in 4000L..40_000L step 3000) session.accept(fix(time = time), time)
        session.accept(fix(-11.9999, 43_000), 43_000)
        assertEquals(listOf(0, 0), session.points.map { it.segment })
        assertEquals(11.12, session.distanceMeters, 0.1)
    }

    @Test fun repeatedStartAndPauseDoNotDoubleCountTime() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.start(4000, 0)
        session.pause(11_000)
        session.pause(15_000)
        assertEquals(10_000L, session.durationMs(99_000))
    }

    private fun position(meters: Double, time: Long, accuracy: Float = 5f) =
        GpsFix(Math.toDegrees(meters / 6_371_000.0), 0.0, accuracy,
            1_700_000_000_000L + time, time)

    @Test fun sameTenKilometersOnFootAndInVehicles() {
        val distances = listOf(1.4, 13.9, 27.8).map { speed ->
            val session = TrackingSession()
            session.start(1000, 0)
            var meters = 0.0
            var time = 1000L
            session.accept(position(meters, time), time)
            while (meters < 10_000.0) {
                meters = (meters + speed).coerceAtMost(10_000.0)
                time += 1000
                session.accept(position(meters, time), time)
            }
            assertEquals("speed=$speed", 10_000.0, session.distanceMeters, 5.1)
            assertEquals(1, session.points.map { it.segment }.distinct().size)
            session.distanceMeters
        }
        assertTrue(distances.max() - distances.min() < 5.1)
    }

    @Test fun trafficStopsCountTimeWithoutAddingDistance() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(position(0.0, 1000), 1000)
        for (i in 1..100) session.accept(position(i * 10.0, 1000L + i * 1000), 1000L + i * 1000)
        for (i in 1..120) {
            val time = 101_000L + i * 1000
            session.accept(position(1000.0 + if (i % 2 == 0) 0.5 else -0.5, time), time)
        }
        assertEquals(1000.0, session.distanceMeters, 0.1)
        assertEquals(220_000L, session.durationMs(221_000))
        assertFalse(session.isGpsStale(221_000))
        for (i in 1..100) session.accept(position(1000.0 + i * 10.0, 221_000L + i * 1000), 221_000L + i * 1000)
        assertEquals(2000.0, session.distanceMeters, 0.1)
    }

    @Test fun jumpAfterTrafficStopIsRejectedUsingRecentSample() {
        val session = TrackingSession()
        session.start(1000, 0)
        for (time in 1000L..121_000L step 1000) session.accept(position(0.0, time), time)
        assertFalse(session.accept(position(1000.0, 122_000), 122_000))
        assertTrue(session.accept(position(10.0, 123_000), 123_000))
        assertEquals(10.0, session.distanceMeters, 0.01)
    }

    @Test fun missingGpsWarnsAndDoesNotInventLostDistance() {
        val session = TrackingSession()
        session.start(1000, 0)
        session.accept(position(0.0, 1000), 1000)
        assertTrue(session.isGpsStale(32_000))
        assertFalse(session.accept(position(2000.0, 32_000, 70f), 32_000))
        assertTrue(session.isGpsStale(33_000))
        session.accept(position(2100.0, 34_000), 34_000)
        session.accept(position(2120.0, 35_000), 35_000)
        assertFalse(session.isGpsStale(35_000))
        assertEquals(20.0, session.distanceMeters, 0.01)
        assertEquals(listOf(0, 1, 1), session.points.map { it.segment })
        session.pause(36_000)
        assertFalse(session.isGpsStale(100_000))
    }

    @Test fun detourIsMeasuredAsPathNotDistanceBetweenEndpoints() {
        for (stepMs in listOf(10_000L, 1000L)) {
            val session = TrackingSession()
            session.start(1000, 0)
            var time = 1000L
            val sideDegrees = Math.toDegrees(100.0 / 6_371_000.0)
            val corners = listOf(0.0 to 0.0, sideDegrees to 0.0,
                sideDegrees to sideDegrees, 0.0 to sideDegrees, 0.0 to 0.0)
            session.accept(GpsFix(0.0, 0.0, 3f, time, time), time)
            for (edge in 0..3) for (i in 1..10) {
                time += stepMs
                val a = corners[edge]; val b = corners[edge + 1]
                session.accept(GpsFix(a.first + (b.first-a.first)*i/10,
                    a.second + (b.second-a.second)*i/10, 3f, time, time), time)
            }
            assertEquals(400.0, session.distanceMeters, 0.1)
        }
    }
}
