package vn.chamcong.iot.ui.employee

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.FaceEnrollmentCommand
import vn.chamcong.iot.model.FaceStation
import vn.chamcong.iot.ui.employees.faceStationBusy

class FaceStationBusyTest {
    private val station = FaceStation(stationId = "GATE-01", pendingCommandId = "request-1")

    @Test fun unknownLockIsBusyUntilConfirmedTerminal() {
        assertTrue(faceStationBusy(station, emptyList()))
        assertTrue(faceStationBusy(station, listOf(FaceEnrollmentCommand(requestId = "request-1", stationId = "GATE-01", status = "CAPTURING"))))
        assertFalse(faceStationBusy(station, listOf(FaceEnrollmentCommand(requestId = "request-1", stationId = "GATE-01", status = "CANCELLED"))))
    }

    @Test fun activeOtherRequestOnStationStillBlocksTerminalLock() {
        val old = FaceEnrollmentCommand(requestId = "request-1", stationId = "GATE-01", status = "COMPLETED")
        val current = FaceEnrollmentCommand(requestId = "request-2", stationId = "GATE-01", status = "FINALIZING")
        assertTrue(faceStationBusy(station, listOf(old, current)))
        assertFalse(faceStationBusy(station.copy(pendingCommandId = ""), listOf(current.copy(stationId = "OTHER"))))
    }
}
