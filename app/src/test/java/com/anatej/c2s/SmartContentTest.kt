package com.anatej.c2s

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartContentTest {

    @Test
    fun `detects links email phone and map conservatively`() {
        val actions = SmartContent.detect("Visit https://example.com/path.")
        assertEquals(SmartContent.Type.URL, actions.single().type)
        assertEquals("https://example.com/path", actions.single().value)

        val email = SmartContent.detect("person@example.com").single()
        assertEquals(SmartContent.Type.EMAIL, email.type)

        val phone = SmartContent.detect("+1 (555) 123-4567").single()
        assertEquals(SmartContent.Type.PHONE, phone.type)
        assertEquals("+15551234567", phone.value)

        val map = SmartContent.detect("12 Main Street").single()
        assertEquals(SmartContent.Type.MAP, map.type)
    }

    @Test
    fun `does not treat arbitrary digit sequences as phone numbers`() {
        val actions = SmartContent.detect("Order 12345678901234567890")
        assertTrue(actions.isEmpty())
    }
}
