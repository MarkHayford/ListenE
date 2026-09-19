package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiBaseUrlTest {
    @Test
    fun debugApiBaseUsesVersionedInternalRoute() {
        assertEquals("http://10.0.2.2:8001/api/v1", BuildConfig.API_BASE_URL)
    }
}
