package com.d365fo.mobileops.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSetupQrTest {

    private fun qr(json: String) = DeviceSetupQr.PREFIX +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun readsTheCodeD365foMakes() {
        val raw = qr("""{"v":1,"label":"Store 12","url":"https://contoso.operations.dynamics.com/","company":"bsp","token":"https://contoso-mo.azurewebsites.net/","code":"0A1B2C3D4E5F60718293A4B5C6D7E8F9"}""")
        val result = DeviceSetupQr.read(raw) as DeviceSetupQr.Result.Ready
        assertEquals("https://contoso.operations.dynamics.com", result.config.resourceUrl)
        assertEquals("https://contoso-mo.azurewebsites.net", result.config.tokenServiceUrl)
        assertEquals("BSP", result.config.company)
        assertEquals("Store 12", result.config.label)
        assertTrue(result.config.usesTokenService)
        assertTrue(result.config.isComplete)
        assertEquals(null, result.config.credentialsError())
    }

    @Test
    fun refusesATokenServiceWithoutHttps() {
        val raw = qr("""{"v":1,"url":"https://contoso.operations.dynamics.com","token":"http://x","code":"AB"}""")
        assertTrue(DeviceSetupQr.read(raw) is DeviceSetupQr.Result.Invalid)
    }

    @Test
    fun isNotAConnectionQr() {
        val raw = qr("""{"v":1,"url":"https://a","token":"https://b","code":"AB"}""")
        assertTrue(DeviceSetupQr.isDeviceSetupQr(raw))
        assertTrue(!ConnectionQr.isConnectionQr(raw))
    }

    @Test
    fun signedTextsAreStable() {
        assertEquals("HSMODEV1-TOKEN\nd1\nhttps://r\n1700000000\nn", TokenServiceClient.tokenText("d1", "https://r", 1700000000, "n"))
        assertEquals("HSMODEV1-REGISTER\nABC\nd1\nPK", TokenServiceClient.registerText(" abc ", "d1", "PK"))
    }
}
