package com.ubers.observador

import com.ubers.observador.domain.backup.BackupLimits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupLimitsTest {
    @Test fun exactBoundaryIsAccepted() {
        val out = ByteArrayOutputStream()
        assertEquals(4L, BackupLimits.copy(byteArrayOf(1,2,3,4).inputStream(), out, 4))
        assertArrayEquals(byteArrayOf(1,2,3,4), out.toByteArray())
    }

    @Test fun anOversizedChunkIsRefusedBeforeItIsWritten() {
        val out = ByteArrayOutputStream()
        try { BackupLimits.copy(ByteArray(8).inputStream(), out, 7); fail("limit") }
        catch (_: BackupLimits.LimitExceeded) { }
        assertEquals(0, out.size())
    }

    @Test fun aStreamWithoutSizeMetadataIsStillBounded() {
        val unknown = object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = 1; return 1 }
        }
        val out = ByteArrayOutputStream()
        try { BackupLimits.copy(unknown, out, 5); fail("limit") }
        catch (_: BackupLimits.LimitExceeded) { }
        assertEquals(5, out.size())
    }

    @Test fun theHashIsVerifiedWithinTheSameStreamingLimit() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BackupLimits.sha256("abc".byteInputStream(), 3))
        try { BackupLimits.sha256("abcd".byteInputStream(), 3); fail("limit") }
        catch (_: BackupLimits.LimitExceeded) { }
    }

    @Test fun zeroBytesAllowsOnlyAnEmptyStream() {
        assertEquals(0L, BackupLimits.copy(byteArrayOf().inputStream(), ByteArrayOutputStream(), 0))
        try { BackupLimits.copy(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), 0); fail("limit") }
        catch (_: BackupLimits.LimitExceeded) { }
    }
}
