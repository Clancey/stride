package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stride restores its own accessibility and notification-listener grants after Android drops them
 * on reinstall. That means writing two secure settings that every other app on the device shares.
 *
 * These lists are the one place where getting it wrong hurts somebody else: silently dropping
 * another app's accessibility service would be a far worse bug than the dead Back button this
 * repairs. So the merge is pinned here rather than trusted.
 */
class SecureListMergeTest {

    private val stride = "io.stride.spikes/io.stride.spikes.StrideAccessibilityService"
    private val other = "com.example.reader/com.example.reader.ReadAloud"

    @Test
    fun `adds our entry to an empty list`() {
        assertEquals(stride, StridePermissions.mergeSecureList(null, stride))
        assertEquals(stride, StridePermissions.mergeSecureList("", stride))
    }

    @Test
    fun `keeps every other app's entry`() {
        assertEquals(
            "$other:$stride",
            StridePermissions.mergeSecureList(other, stride),
        )
    }

    @Test
    fun `reports no change when we are already enabled`() {
        // Returning null rather than the same string is what stops a repair pass writing a secure
        // setting on every launch when nothing is actually wrong.
        assertNull(StridePermissions.mergeSecureList(stride, stride))
        assertNull(StridePermissions.mergeSecureList("$other:$stride", stride))
    }

    @Test
    fun `does not grow the value across repeated repairs`() {
        // Android leaves trailing and doubled colons in these keys. Appending blindly would make
        // the value longer every time a repair ran, forever.
        val messy = "$other::"
        val once = StridePermissions.mergeSecureList(messy, stride)
        assertEquals("$other:$stride", once)
        assertNull(StridePermissions.mergeSecureList(once, stride))
    }

    @Test
    fun `a similarly named component is not mistaken for ours`() {
        val impostor = "io.stride.spikes.other/io.stride.spikes.StrideAccessibilityService"
        assertEquals(
            "$impostor:$stride",
            StridePermissions.mergeSecureList(impostor, stride),
        )
    }
}
