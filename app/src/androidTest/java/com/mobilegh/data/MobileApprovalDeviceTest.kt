package com.mobilegh.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

/** Opt-in real-device check. Credentials are read from a private test fixture and are never test output. */
class MobileApprovalDeviceTest {
    @Test fun importedKeyInAndroidKeystoreMatchesScriptDeviceAndNativeSigningBytes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("mobilegh2faBundle")
        assumeTrue("Provide a script-exported bundle to run this integration test", path != null)
        require(path!!.matches(Regex("/data/local/tmp/[A-Za-z0-9_.-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = instrumentation.uiAutomation.executeShellCommand("cat $path").use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val bundle = MobileApprovalProtocol.parseBundle(text)
        val context = instrumentation.targetContext
        val device = MobileApproval.importDevice(context, text)
        assertEquals(bundle.getValue("login").jsonPrimitive.content, device.login)
        val request = MobileApprovalRequest(123, "AAH/gA==", true, "TWO_FACTOR_LOGIN")
        val message = MobileApprovalProtocol.signingMessage(request, "007")
        val signature = MobileApproval.sign(device.alias, message)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(
            Base64.getDecoder().decode(bundle.getValue("certificate").jsonPrimitive.content)))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(certificate); update(byteArrayOf(49,124,0,1,-1,-128,124,55))
            verify(Base64.getMimeDecoder().decode(signature))
        })
        MobileApproval.poll(context, device)
        Unit
    }
}
