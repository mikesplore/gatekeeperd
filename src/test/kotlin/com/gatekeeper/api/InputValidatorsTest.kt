package com.gatekeeper.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputValidatorsTest {

    @Test
    fun `isValidContainerRef accepts name only`() {
        assertTrue(InputValidators.isValidContainerRef("redis"))
        assertTrue(InputValidators.isValidContainerRef("my-app_1"))
    }

    @Test
    fun `isValidContainerRef accepts name colon port`() {
        assertTrue(InputValidators.isValidContainerRef("acw:9921"))
        assertTrue(InputValidators.isValidContainerRef("acw:1"))
        assertTrue(InputValidators.isValidContainerRef("acw:65535"))
    }

    @Test
    fun `isValidContainerRef rejects invalid port`() {
        assertFalse(InputValidators.isValidContainerRef("acw:0"))
        assertFalse(InputValidators.isValidContainerRef("acw:65536"))
        assertFalse(InputValidators.isValidContainerRef("acw:not-a-port"))
        assertFalse(InputValidators.isValidContainerRef("acw:"))
    }
}

