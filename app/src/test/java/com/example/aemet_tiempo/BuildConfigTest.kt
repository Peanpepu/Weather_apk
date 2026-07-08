package com.example.aemet_tiempo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Test to verify that BuildConfig is properly generated with the API key from .env
 */
class BuildConfigTest {
    
    @Test
    fun testAemetApiKeyIsPresent() {
        // Verify that the API key is present and not empty
        val apiKey = BuildConfig.AEMET_API_KEY
        assertNotNull("AEMET_API_KEY should not be null", apiKey)
        assert(apiKey.isNotEmpty()) { "AEMET_API_KEY should not be empty" }
    }
    
    @Test
    fun testAemetApiKeyFormat() {
        // Verify that the API key has the expected JWT format (3 parts separated by dots)
        val apiKey = BuildConfig.AEMET_API_KEY
        val parts = apiKey.split(".")
        assertEquals("JWT should have 3 parts", 3, parts.size)
    }
}
