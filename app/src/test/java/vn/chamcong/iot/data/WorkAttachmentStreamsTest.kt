package vn.chamcong.iot.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import vn.chamcong.iot.model.WorkItemAttachment

class WorkAttachmentStreamsTest {
    @Test fun `accepts bytes exactly at five MiB limit`() {
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        assertArrayEquals(bytes, readBoundedWorkAttachment(ByteArrayInputStream(bytes)))
    }

    @Test fun `unknown stream size stops after limit plus one byte`() {
        var read = 0
        val stream = object : InputStream() {
            override fun read(): Int { read++; return 42 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(42, offset, offset + length)
                read += length
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { readBoundedWorkAttachment(stream) }
        assertEquals(5 * 1024 * 1024 + 1, read)
    }

    @Test fun `zero returning provider cannot hang and empty files fail`() {
        val stream = object : InputStream() {
            private var consumed = false
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun read(): Int = if (consumed) -1 else { consumed = true; 17 }
        }
        assertArrayEquals(byteArrayOf(17), readBoundedWorkAttachment(stream))
        assertThrows(IllegalArgumentException::class.java) { readBoundedWorkAttachment(ByteArrayInputStream(byteArrayOf())) }
    }

    @Test fun `provider filenames stay inside cache and preserve usable extension`() {
        assertEquals("anh.png", sanitizeWorkAttachmentFileName("../../anh.png"))
        assertEquals("ket-qua.pdf", sanitizeWorkAttachmentFileName("C:\\folder\\ket-qua.pdf"))
        assertEquals("tep-dinh-kem", sanitizeWorkAttachmentFileName(".."))
        assertEquals("anh.png", sanitizeWorkAttachmentFileName("\nanh.png\u0000"))
        assertEquals(180, sanitizeWorkAttachmentFileName("a".repeat(500)).length)
    }

    @Test fun `Unicode display names do not exceed physical filename byte limit`() {
        val attachment = WorkItemAttachment(id = "a".repeat(64), fileName = "ấ".repeat(175) + ".pdf")
        assertEquals("a".repeat(64) + ".pdf", workAttachmentCacheFileName(attachment))
        assertEquals(68, workAttachmentCacheFileName(attachment).toByteArray(Charsets.UTF_8).size)
        assertEquals(attachment.id, workAttachmentCacheFileName(attachment.copy(fileName = "result.unknown-extension")))
        assertEquals(attachment.id, workAttachmentCacheFileName(attachment.copy(fileName = "result." + "a".repeat(20))))
    }
}
