package com.melody.melodylink.xiaomi

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue

data class XiaomiRcspFrame(
    val control: Int,
    val opcode: Int,
    val parameter: ByteArray,
) {
    val isCommand get() = control and 0x80 != 0
    val status get() = if (isCommand || parameter.size < 2) null else parameter[0].toInt() and 0xFF
    val sequence get() = if (isCommand || parameter.size < 2) null else parameter[1].toInt() and 0xFF
    val commandSequence get() = if (isCommand && parameter.isNotEmpty()) parameter[0].toInt() and 0xFF else null
    val payload get() = when {
        isCommand && parameter.isNotEmpty() -> parameter.copyOfRange(1, parameter.size)
        !isCommand && parameter.size >= 2 -> parameter.copyOfRange(2, parameter.size)
        else -> ByteArray(0)
    }
}

object XiaomiRcspCodec {
    private val header = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
    private const val BLE_TARGET_APP = 0x01
    const val SPP_TARGET_APP = 0x04

    fun getTargetInfo(sequence: Int, targetApp: Int = BLE_TARGET_APP): ByteArray = command(
        opcode = 0x02,
        sequence = sequence,
        payload = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        targetApp = targetApp,
    )

    fun getConfigs(
        sequence: Int,
        ids: IntArray = DEFAULT_CONFIG_IDS,
        targetApp: Int = BLE_TARGET_APP,
    ): ByteArray = command(
        opcode = 0xF3,
        sequence = sequence,
        payload = ids.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray(),
        targetApp = targetApp,
    )

    fun setAnc(sequence: Int, rawMode: ByteArray, targetApp: Int = BLE_TARGET_APP): ByteArray {
        require(rawMode.size == 2) { "Xiaomi ANC mode must contain two bytes" }
        return command(0xF2, sequence, byteArrayOf(0x04, 0x00, 0x0B) + rawMode, targetApp = targetApp)
    }

    /** Redmi Buds 6 (O77) uses SetTargetInfo, not the generic F2 configuration item. */
    fun setO77Anc(sequence: Int, mode: AncMode): ByteArray = command(
        opcode = 0x08,
        sequence = sequence,
        payload = byteArrayOf(0x02, 0x04, o77AncValue(mode).toByte()),
        targetApp = SPP_TARGET_APP,
    )

    private fun o77AncValue(mode: AncMode): Int = when (mode) {
        AncMode.OFF -> 0x00
        AncMode.NOISE_CANCELING -> 0x01
        AncMode.AMBIENT_SOUND, AncMode.TRANSPARENCY -> 0x02
    }

    fun command(
        opcode: Int,
        sequence: Int,
        payload: ByteArray,
        responseRequired: Boolean = true,
        targetApp: Int = BLE_TARGET_APP,
    ): ByteArray {
        require(targetApp in 0..0x0F) { "Xiaomi RCSP target app is out of range" }
        val parameter = byteArrayOf(sequence.toByte()) + payload
        val control = 0x80 or targetApp or if (responseRequired) 0x40 else 0
        return header + byteArrayOf(control.toByte(), opcode.toByte(), (parameter.size ushr 8).toByte(), parameter.size.toByte()) + parameter + byteArrayOf(0xEF.toByte())
    }

    fun decode(frame: ByteArray): XiaomiRcspFrame? {
        if (frame.size < 8 || !frame.copyOfRange(0, 3).contentEquals(header) || frame.last() != 0xEF.toByte()) return null
        val length = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
        if (frame.size != length + 8) return null
        return XiaomiRcspFrame(frame[3].toInt() and 0xFF, frame[4].toInt() and 0xFF, frame.copyOfRange(7, frame.lastIndex))
    }

    val DEFAULT_CONFIG_IDS = intArrayOf(0x0001, 0x0002, 0x0003, 0x0004, 0x000A, 0x000B, 0x000F, 0x0024)
}

/**
 * Redmi Buds 6 publishes state on the SPP target-app channel.  Its payload is a
 * sequence of LEN | TYPE | DATA TLVs: type 7 is returned by GetTargetInfo and
 * type 0 is sent by device-status notifications.  Both hold left/right/case bytes.
 */
object XiaomiO77StatusParser {
    fun parse(frame: XiaomiRcspFrame): EarbudsStateUpdate? {
        // O77 replies use target-app 4, while unsolicited 0x0E status notifications
        // arrive on its companion target-app 7 channel (control 0xC7 in device logs).
        if (frame.control and 0x0F !in setOf(XiaomiRcspCodec.SPP_TARGET_APP, 0x07)) return null
        val allowedTypes = when {
            !frame.isCommand && frame.opcode == 0x02 && frame.status == 0 -> setOf(7)
            frame.isCommand && frame.opcode == 0x0E -> setOf(0, 4)
            else -> return null
        }
        var battery: Map<BatteryPart, BatteryValue>? = null
        var mode: AncMode? = null
        parseTlvs(frame.payload)?.forEach { (type, data) ->
            when (type) {
                in allowedTypes.filter { it == 0 } -> parseBattery(data)?.let { battery = it }
                7 -> if (7 in allowedTypes) parseBattery(data)?.let { battery = it }
                4 -> if (4 in allowedTypes && data.size == 1) mode = when (data[0].toInt() and 0xFF) {
                    0 -> AncMode.OFF
                    1 -> AncMode.NOISE_CANCELING
                    2 -> AncMode.AMBIENT_SOUND
                    else -> null
                }
            }
        }
        return EarbudsStateUpdate(battery, mode).takeIf { it.battery != null || it.ancMode != null }
    }

    private fun parseTlvs(payload: ByteArray): List<Pair<Int, ByteArray>>? {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset < payload.size) {
            val length = payload[offset].toInt() and 0xFF
            if (length < 1 || offset + length >= payload.size) return null
            val type = payload[offset + 1].toInt() and 0xFF
            result += type to payload.copyOfRange(offset + 2, offset + length + 1)
            offset += length + 1
        }
        return result
    }

    private fun parseBattery(data: ByteArray): Map<BatteryPart, BatteryValue>? {
        if (data.size != 3) return null
        val parts = listOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)
        val result = mutableMapOf<BatteryPart, BatteryValue>()
        parts.forEachIndexed { index, part ->
            val raw = data[index].toInt() and 0xFF
            if (raw == 0xFF) return@forEachIndexed
            if (raw and 0x7F > 100) return null
            result[part] = BatteryValue(raw and 0x7F, raw and 0x80 != 0)
        }
        return result
    }
}

data class EarbudsStateUpdate(
    val battery: Map<BatteryPart, BatteryValue>?,
    val ancMode: AncMode?,
)

class XiaomiRcspStreamDecoder {
    private val bytes = ArrayList<Byte>()

    fun accept(chunk: ByteArray): List<XiaomiRcspFrame> {
        chunk.forEach(bytes::add)
        val frames = mutableListOf<XiaomiRcspFrame>()
        while (bytes.size >= 8) {
            val start = findHeader()
            if (start < 0) {
                val keep = minOf(bytes.size, 2)
                repeat(bytes.size - keep) { bytes.removeAt(0) }
                break
            }
            repeat(start) { bytes.removeAt(0) }
            if (bytes.size < 8) break
            val length = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
            val total = length + 8
            if (length > 4096) { bytes.removeAt(0); continue }
            if (bytes.size < total) break
            val raw = ByteArray(total) { bytes[it] }
            if (raw.last() == 0xEF.toByte()) {
                XiaomiRcspCodec.decode(raw)?.let(frames::add)
                repeat(total) { bytes.removeAt(0) }
            } else {
                bytes.removeAt(0)
            }
        }
        return frames
    }

    private fun findHeader(): Int {
        for (index in 0..bytes.size - 3) {
            if (bytes[index] == 0xFE.toByte() && bytes[index + 1] == 0xDC.toByte() && bytes[index + 2] == 0xBA.toByte()) return index
        }
        return -1
    }
}

data class XiaomiConfigItem(val id: Int, val data: ByteArray)

object XiaomiConfigParser {
    fun parse(responsePayload: ByteArray): List<XiaomiConfigItem>? {
        val result = mutableListOf<XiaomiConfigItem>()
        var offset = 0
        while (offset < responsePayload.size) {
            val length = responsePayload[offset].toInt() and 0xFF
            if (length < 2 || offset + length >= responsePayload.size) return null
            val id = ((responsePayload[offset + 1].toInt() and 0xFF) shl 8) or (responsePayload[offset + 2].toInt() and 0xFF)
            result += XiaomiConfigItem(id, responsePayload.copyOfRange(offset + 3, offset + length + 1))
            offset += length + 1
        }
        return result
    }
}

/** Only unambiguous no-level modes are admitted. Unknown and strength-only mode tables stay read-only. */
data class XiaomiAncPolicy private constructor(private val codes: Map<AncMode, ByteArray>) {
    fun codeFor(mode: AncMode): ByteArray? = codes[mode]?.copyOf()
    fun modeFor(raw: ByteArray): AncMode? = codes.entries.firstOrNull { it.value.contentEquals(raw) }?.key

    companion object {
        fun fromCapabilityString(value: String): XiaomiAncPolicy? {
            val fields = value.split(',')
            if (fields.size < 2) return null
            val modes = fields[1].split(';').map(String::trim).filter { it.matches(Regex("[0-9A-Fa-f]{4}")) }.map { it.uppercase() }.toSet()
            if (!setOf("0100", "0101", "0201").all(modes::contains)) return null
            if (modes.any { it.startsWith("01") && it !in setOf("0100", "0101") }) return null
            if (modes.any { it.startsWith("02") && it != "0201" }) return null
            return XiaomiAncPolicy(mapOf(
                AncMode.OFF to byteArrayOf(0x01, 0x00),
                AncMode.NOISE_CANCELING to byteArrayOf(0x01, 0x01),
                AncMode.TRANSPARENCY to byteArrayOf(0x02, 0x01),
            ))
        }
    }
}
