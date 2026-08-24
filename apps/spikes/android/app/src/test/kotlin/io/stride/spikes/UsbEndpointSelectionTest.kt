package io.stride.spikes

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which USB endpoints the direct path is willing to talk over.
 *
 * This exists because of a field report that took three rounds to explain. An X22i owner reported
 * that direct hardware access found nothing, on a console where `dumpsys usb` showed the board
 * present, enumerated, and with permission already granted to Stride's own uid:
 *
 * ```
 * vendor_id=8508 (0x213C)  product_id=2   ICON Fitness / "ICON Generic HID"
 *   interface 0, class=3 (HID), "USB Data Interface"
 *     endpoint 1: address 0x81, IN,  interrupt, max_packet_size=64
 *     endpoint 2: address 0x02, OUT, interrupt, max_packet_size=64
 * ```
 *
 * Stride required **bulk** endpoints. HID uses interrupt — always, by definition of the class — so
 * the interface search matched nothing, `open` returned null, and the rider was told there was no
 * USB connection to a treadmill that was sitting right there. No amount of protocol work above this
 * layer could ever have been reached.
 *
 * `UsbEndpoint` is final and cannot be mocked without a framework, so these assert on the predicate
 * that decides it. That is the whole of the rule.
 */
class UsbEndpointSelectionTest {

    /** The X22i's console board. The case that was being rejected. */
    @Test
    fun `an interrupt endpoint is a usable data pipe`() {
        assertTrue(UsbSerialTransport.isDataPipeType(UsbConstants.USB_ENDPOINT_XFER_INT))
    }

    /** Still accepted, so a board that does use bulk is not broken by the fix. */
    @Test
    fun `a bulk endpoint remains a usable data pipe`() {
        assertTrue(UsbSerialTransport.isDataPipeType(UsbConstants.USB_ENDPOINT_XFER_BULK))
    }

    /**
     * Control endpoint 0 exists on every USB device and is not a data pipe.
     *
     * Accepting it would make the interface search succeed on essentially anything, which is worse
     * than the bug being fixed: it would put register writes onto a pipe the device uses for
     * enumeration.
     */
    @Test
    fun `the control endpoint is not a data pipe`() {
        assertFalse(UsbSerialTransport.isDataPipeType(UsbConstants.USB_ENDPOINT_XFER_CONTROL))
    }

    /**
     * Isochronous is excluded on purpose: it drops data by design.
     *
     * That is fine for audio and unacceptable for a command that changes how fast a belt moves.
     */
    @Test
    fun `an isochronous endpoint is not a data pipe`() {
        assertFalse(UsbSerialTransport.isDataPipeType(UsbConstants.USB_ENDPOINT_XFER_ISOC))
    }
}
