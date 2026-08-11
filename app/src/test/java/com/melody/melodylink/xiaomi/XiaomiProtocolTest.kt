package com.melody.melodylink.xiaomi

import com.melody.melodylink.domain.AncMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiProtocolTest {
    @Test
    fun encodesF2AncWithoutAControlLevel() {
        val packet = XiaomiRcspCodec.setAnc(0x2A, byteArrayOf(0x01, 0x01))
        assertEquals("FE DC BA C1 F2 00 06 2A 04 00 0B 01 01 EF", packet.hex())
    }

    @Test
    fun encodesSppTargetInfoWithMibudstestControlBits() {
        val packet = XiaomiRcspCodec.getTargetInfo(0x07, XiaomiRcspCodec.SPP_TARGET_APP)
        assertEquals("FE DC BA C4 02 00 05 07 FF FF FF FF EF", packet.hex())
    }

    @Test
    fun encodesO77AncWithVerifiedLegacyTargetInfoPath() {
        val packet = XiaomiRcspCodec.setO77Anc(0x07, AncMode.AMBIENT_SOUND)
        assertEquals("FE DC BA C4 08 00 04 07 02 04 02 EF", packet.hex())
    }

    @Test
    fun streamDecoderHandlesNoiseAndFragmentation() {
        val response = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x01, 0xF3.toByte(), 0x00, 0x07, 0x00, 0x21, 0x04, 0x00, 0x0B, 0x01, 0x00, 0xEF.toByte())
        val decoder = XiaomiRcspStreamDecoder()
        assertTrue(decoder.accept(byteArrayOf(0x00, 0x11) + response.copyOfRange(0, 9)).isEmpty())
        val frame = decoder.accept(response.copyOfRange(9, response.size)).single()
        assertEquals(0xF3, frame.opcode)
        assertEquals(0x21, frame.sequence)
    }

    @Test
    fun configParserRejectsTruncatedItems() {
        assertNull(XiaomiConfigParser.parse(byteArrayOf(0x04, 0x00, 0x0B, 0x01)))
        val item = XiaomiConfigParser.parse(byteArrayOf(0x04, 0x00, 0x0B, 0x01, 0x00))!!.single()
        assertEquals(0x000B, item.id)
        assertArrayEquals(byteArrayOf(0x01, 0x00), item.data)
    }

    @Test
    fun onlyUnambiguousThreeModeCapabilityEnablesAnc() {
        val policy = XiaomiAncPolicy.fromCapabilityString("a,0100;0101;0201,c,d")
        assertNotNull(policy)
        assertArrayEquals(byteArrayOf(0x01, 0x01), policy!!.codeFor(AncMode.NOISE_CANCELING))
        assertNull(XiaomiAncPolicy.fromCapabilityString("a,0100;0102;0201,c,d"))
        assertNull(XiaomiAncPolicy.fromCapabilityString("a,0100;0101;0104;0201,c,d"))
    }

    @Test
    fun parsesThreeBatteryValuesAndKeepsUnknownAbsent() {
        val values = XiaomiBatteryParser.parseVendorEvent("129,64,255,0")
        assertEquals(1, values[com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertTrue(values[com.melody.melodylink.domain.BatteryPart.LEFT]?.charging == true)
        assertEquals(64, values[com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(values.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesSplitXiaomiVendorBatteryArguments() {
        val values = XiaomiBatteryParser.parseVendorEvent(listOf("+XIAOMI", "129,64,255,0,0,0"))
        assertEquals(1, values[com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertEquals(64, values[com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(values.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesO77BatteryFromTargetInfoTlvs() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x04, 0x02, 0x00, 0x07,
            0x00, 0x21, 0x04, 0x07, 0x81.toByte(), 0x40, 0xFF.toByte(), 0xEF.toByte(),
        ))!!
        val update = XiaomiO77StatusParser.parse(frame)!!
        assertEquals(1, update.battery!![com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertTrue(update.battery!![com.melody.melodylink.domain.BatteryPart.LEFT]?.charging == true)
        assertEquals(64, update.battery!![com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(update.battery!!.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesO77StatusOnCompanionNotificationChannel() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC7.toByte(), 0x0E, 0x00, 0x04,
            0x31, 0x02, 0x04, 0x01, 0xEF.toByte(),
        ))!!
        assertEquals(AncMode.NOISE_CANCELING, XiaomiO77StatusParser.parse(frame)?.ancMode)
    }

    @Test
    fun ignoresO77StrengthOnlyNotification() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC7.toByte(), 0xF4.toByte(), 0x00, 0x06,
            0x33, 0x04, 0x00, 0x0B, 0x01, 0x02, 0xEF.toByte(),
        ))!!
        assertNull(XiaomiO77StatusParser.parse(frame))
    }
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
