package com.melody.melodylink.huawei.config

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.DeviceIdentity
import java.util.UUID

/** Model routes and capabilities verified against Nshpiter/HuaweiPods. */
enum class HuaweiDeviceRoute(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
    val supportsAnc: Boolean,
    val supportsTransparency: Boolean,
    val supportsAncReadback: Boolean,
    val supportsAncLevels: Boolean,
    val batteryParts: Set<BatteryPart>,
) {
    FREEBUDS3("huawei.freebuds3", "HUAWEI FreeBuds 3", setOf("huaweifreebuds3", "freebuds3"), true, false, false, true, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS5("huawei.freebuds5", "HUAWEI FreeBuds 5", setOf("huaweifreebuds5", "freebuds5"), true, false, true, true, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS6I("huawei.freebuds6i", "HUAWEI FreeBuds 6i", setOf("huaweifreebuds6i", "freebuds6i"), true, true, true, true, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS_PRO3("huawei.freebuds_pro3", "HUAWEI FreeBuds Pro 3", setOf("huaweifreebudspro3", "freebudspro3"), true, true, true, true, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS_PRO4("huawei.freebuds_pro4", "HUAWEI FreeBuds Pro 4", setOf("huaweifreebudspro4", "freebudspro4"), true, false, false, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS_PRO5("huawei.freebuds_pro5", "HUAWEI FreeBuds Pro 5", setOf("huaweifreebudspro5", "freebudspro5"), true, true, true, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREEBUDS7I("huawei.freebuds7i", "HUAWEI FreeBuds 7i", setOf("huaweifreebuds7i", "freebuds7i"), true, true, true, true, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREECLIP("huawei.freeclip", "HUAWEI FreeClip", setOf("huaweifreeclip", "freeclip"), false, false, false, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    FREECLIP2("huawei.freeclip2", "HUAWEI FreeClip 2", setOf("huaweifreeclip2", "freeclip2"), false, false, false, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)),
    EYEWEAR("huawei.eyewear", "HUAWEI Eyewear", setOf("huaweieyewear"), false, false, false, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT)),
    EYEWEAR2("huawei.eyewear2", "HUAWEI Eyewear 2", setOf("huaweieyewear2", "eyewear2"), false, false, false, false, setOf(BatteryPart.LEFT, BatteryPart.RIGHT)),
}

object HuaweiUuids {
    val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}

data class HuaweiDeviceMatch(val route: HuaweiDeviceRoute, val confidence: Int)

object HuaweiDeviceCatalog {
    val models: List<HuaweiDeviceRoute> = HuaweiDeviceRoute.entries

    fun find(identity: DeviceIdentity): HuaweiDeviceMatch? {
        val name = normalize(identity.bluetoothName)
        if (name.isBlank()) return null
        val route = models
            .sortedByDescending { model -> model.aliases.maxOf(String::length) }
            .firstOrNull { model -> model.aliases.any { alias -> name == alias || name.contains(alias) } }
            ?: return null
        return HuaweiDeviceMatch(route, if (HuaweiUuids.spp.toString() in identity.serviceUuids.map(String::lowercase)) 100 else 70)
    }

    private fun normalize(value: String?): String = value.orEmpty().lowercase().filter(Char::isLetterOrDigit)
}
