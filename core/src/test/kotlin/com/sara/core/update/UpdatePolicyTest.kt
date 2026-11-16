package com.sara.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun `lee el numero del tag`() {
        assertEquals(12, UpdatePolicy.versionFromTag("SaraAssistantV12"))
        assertEquals(3, UpdatePolicy.versionFromTag(" saraassistantv3 "))
        assertNull(UpdatePolicy.versionFromTag("v1.0.3"))
        assertNull(UpdatePolicy.versionFromTag("SaraAssistantV"))
    }

    @Test
    fun `solo avisa si la version remota es mayor`() {
        assertTrue(UpdatePolicy.isNewer("SaraAssistantV5", 4))
        assertFalse(UpdatePolicy.isNewer("SaraAssistantV5", 5))
        assertFalse(UpdatePolicy.isNewer("SaraAssistantV4", 5))
        assertFalse(UpdatePolicy.isNewer("otro-tag", 1))
    }
}
