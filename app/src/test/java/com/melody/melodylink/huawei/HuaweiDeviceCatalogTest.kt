package com.melody.melodylink.huawei

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.huawei.config.HuaweiDeviceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuaweiDeviceCatalogTest {
    @Test fun matchesNormalizedFreeBudsName() {
        val match = HuaweiDeviceCatalog.find(DeviceIdentity(bluetoothName = "HUAWEI FreeBuds 7i"))
        assertEquals("huawei.freebuds7i", match?.route?.id)
        assertEquals(70, match?.confidence)
    }

    @Test fun rejectsUnknownName() {
        assertNull(HuaweiDeviceCatalog.find(DeviceIdentity(bluetoothName = "Random Headset")))
    }
}
