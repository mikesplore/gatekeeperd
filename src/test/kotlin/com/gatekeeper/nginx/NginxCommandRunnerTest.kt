package com.gatekeeper.nginx

import com.gatekeeper.config.AppEnvironment
import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NginxCommandRunnerTest {
    val policy = NginxEnvironmentPolicy(
        environment = AppEnvironment.DEV,
        nginxHostSudoEnabled = false,
        nginxCertbotEnabled = false,
        customCertDirectory = null
    )

    @Test
    fun `nginx test preserves command output and exit status`() {
        val runner = NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "configuration error")), policy)

        val result = runner.test()

        assertFalse(result.valid)
        assertEquals(1, result.exitCode)
        assertEquals("configuration error", result.output)
    }

    @Test
    fun `reload reports command success`() {
        assertTrue(NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "", "")), policy).reload())
        assertFalse(NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "failed")), policy).reload())
    }

    @Test
    fun `configuration dump is returned only for successful command`() {
        assertEquals(
            "http {}",
            NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "http {}", "")), policy).dumpConfig()
        )
        assertEquals(null, NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "failed")), policy).dumpConfig())
    }

    private class FakeProcessRunner(private val result: ProcessResult) : ProcessRunner {
        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            environment: Map<String, String>,
            stdin: String?
        ): ProcessResult = result
    }


    @Test
    fun `nginx test respects DEV no-op policy by default`(): Unit {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = false,
            nginxCertbotEnabled = false,
            customCertDirectory = null
        )
        val runner = NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "syntax is ok", "")), policy)

        val result = runner.test()

        // DEV by default disables host mutation -> test command is `true`
        assertTrue(result.valid)
        assertEquals(0, result.exitCode)
        assertEquals("syntax is ok", result.output)
    }

    @Test
    fun `nginx reload respects DEV no-op policy`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = false,
            nginxCertbotEnabled = false,
            customCertDirectory = null
        )
        val runner = NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "", "")), policy)

        assertTrue(runner.reload())
    }

    @Test
    fun `nginx dump respects PROD sudo policy`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.PROD,
            nginxHostSudoEnabled = true,
            nginxCertbotEnabled = true,
            customCertDirectory = null
        )
        val runner = NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "nginx -T output", "")), policy)

        assertEquals("nginx -T output", runner.dumpConfig())
    }

    @Test
    fun `certbot is disabled in DEV when flag is false`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = false,
            nginxCertbotEnabled = false,
            customCertDirectory = null
        )
        assertEquals(false, policy.certbotEnabled)
        assertEquals(false, policy.isHostMutationAllowed)
    }

    @Test
    fun `certbot is enabled in PROD`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.PROD,
            nginxHostSudoEnabled = true,
            nginxCertbotEnabled = true,
            customCertDirectory = null
        )
        assertEquals(true, policy.certbotEnabled)
    }

    @Test
    fun `opt-in local nginx binary in DEV enables sudo-less host mutation`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = true,
            nginxCertbotEnabled = false,
            customCertDirectory = null
        )
        assertTrue(policy.isHostMutationAllowed)
        assertEquals(listOf("/usr/sbin/nginx", "-t"), policy.nginxTestCommand())
    }

    @Test
    fun `custom cert directory is only allowed in DEV`() {
        val devPolicy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = false,
            nginxCertbotEnabled = false,
            customCertDirectory = "/tmp/certs"
        )
        assertTrue(devPolicy.customCertDirectoryAllowed)
        assertEquals("/tmp/certs", devPolicy.customCertDirectory)

        val prodPolicy = NginxEnvironmentPolicy(
            environment = AppEnvironment.PROD,
            nginxHostSudoEnabled = true,
            nginxCertbotEnabled = true,
            customCertDirectory = null
        )
        assertFalse(prodPolicy.customCertDirectoryAllowed)
    }
}
