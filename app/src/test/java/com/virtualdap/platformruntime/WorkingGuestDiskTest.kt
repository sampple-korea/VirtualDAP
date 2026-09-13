package com.virtualdap.platformruntime

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkingGuestDiskTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun guestChangesSurviveRestartWithoutReimportingTheBase() {
        val base = byteArrayOf(1, 2, 3, 4)
        val store = WorkingGuestDisk(temporary.newFolder())
        val disk = store.prepare(base.inputStream(), base.size.toLong(), sha256(base))
        val guestChanges = byteArrayOf(9, 8, 7, 6)
        disk.writeBytes(guestChanges)
        val unreadableSource = object : ByteArrayInputStream(base) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                error("A restart must not recopy the base image")
        }

        val resumed = store.prepare(unreadableSource, base.size.toLong(), sha256(base)) { 0 }

        assertEquals(disk, resumed)
        assertArrayEquals(guestChanges, resumed.readBytes())
    }

    @Test fun aNewImageDoesNotOverwriteExistingGuestData() {
        val store = WorkingGuestDisk(temporary.newFolder())
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val original = store.prepare(first.inputStream(), 3, sha256(first))
        original.writeBytes(byteArrayOf(7, 8, 9))

        val replacement = store.prepare(second.inputStream(), 3, sha256(second))

        assertArrayEquals(second, replacement.readBytes())
        assertArrayEquals(byteArrayOf(7, 8, 9), original.readBytes())
    }

    @Test fun corruptSourceLeavesNoActivatedDiskAndNoPartialCopy() {
        val root = temporary.newFolder()
        val store = WorkingGuestDisk(root)
        assertThrows(IllegalArgumentException::class.java) {
            store.prepare(byteArrayOf(4, 5, 6).inputStream(), 3, sha256(byteArrayOf(1, 2, 3)))
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun aTruncatedSavedDiskIsReportedWithoutWipingIt() {
        val store = WorkingGuestDisk(temporary.newFolder())
        val base = byteArrayOf(1, 2, 3)
        val disk = store.prepare(base.inputStream(), 3, sha256(base))
        disk.writeBytes(byteArrayOf(8))

        assertThrows(IllegalStateException::class.java) {
            store.prepare(base.inputStream(), 3, sha256(base))
        }
        assertArrayEquals(byteArrayOf(8), disk.readBytes())
    }

    @Test fun storageFailureDoesNotLeaveAPartialDisk() {
        val root = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            WorkingGuestDisk(root).prepare(byteArrayOf(1).inputStream(), 1, sha256(byteArrayOf(1))) { 0 }
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
