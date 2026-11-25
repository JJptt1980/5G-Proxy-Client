package com.tokyoxpa3.socksclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test fun bootNeedsAutoStartButUpdateDoesNot() {
        assertTrue(BootReceiver.needsAutoStartSetting("android.intent.action.BOOT_COMPLETED"))
        assertTrue(BootReceiver.needsAutoStartSetting("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(BootReceiver.needsAutoStartSetting("android.intent.action.MY_PACKAGE_REPLACED"))
    }
}
