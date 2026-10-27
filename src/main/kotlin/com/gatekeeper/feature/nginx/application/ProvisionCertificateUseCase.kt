package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner

class ProvisionCertificateUseCase(
    private val provisioner: CertificateProvisioner,
    private val certificateRepository: CertificateRepository,
    private val commandRunner: NginxCommandRunner
) {
    fun execute(domain: String, email: String): Boolean {
        val success = provisioner.installCertificate(domain, email)
        if (!success) return false

        certificateRepository.syncInventory(domain, expiresAt = null, renewalStatus = "installed")
        return commandRunner.reload()
    }
}