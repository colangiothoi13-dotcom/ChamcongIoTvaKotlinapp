package vn.chamcong.iot.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraPreviewConnectionTest {
    private val pin = "AA:".repeat(31) + "AA"
    @Test fun pairsPrivateHttpsAddressAndFullCertificatePin() {
        assertEquals(CameraPreviewConnection("https://192.168.1.10:4174", "AA".repeat(32)), cameraPreviewConnection("https://192.168.1.10/", pin))
        assertEquals("https://10.0.2.2:5000", cameraPreviewConnection("https://10.0.2.2:5000", pin).baseUrl)
    }
    @Test fun rejectsPlainHttpPublicHostRedirectPathOrCredentialsBeforeTokenIsSent() {
        for (address in listOf("http://192.168.1.10:4174", "https://example.com", "https://10.1.2.3.a", "https://010.1.2.3", "https://user:pass@192.168.1.10", "https://192.168.1.10/capture", "https://192.168.1.10?url=x", "https://192.168.1.10#x", "https://192.168.999.10")) {
            assertThrows(IllegalArgumentException::class.java) { cameraPreviewConnection(address, pin) }
        }
    }
    @Test fun rejectsIncompleteOrNonHexFingerprint() {
        assertThrows(IllegalArgumentException::class.java) { cameraPreviewConnection("https://192.168.1.10", "AA") }
        assertThrows(IllegalArgumentException::class.java) { cameraPreviewConnection("https://192.168.1.10", "GG".repeat(32)) }
    }
}
