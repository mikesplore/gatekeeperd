package com.gatekeeper.feature.nginx.domain.port

import com.gatekeeper.feature.nginx.model.ResolvedCertificate


interface CertificateProvisioner {
    fun isCertbotAvailable(): Boolean
    fun certificateManagerIssue(): String?
    fun installCertificate(domain: String, email: String): Boolean
    fun renewCertificate(domain: String): Boolean
    fun removeCertificate(domain: String): Boolean
    fun isCertificateInstalled(domain: String): Boolean
    fun resolveCertificateForDomain(domain: String, requestedCertificateDomain: String? = null): ResolvedCertificate?
    fun listInstalledCertificates(): List<ResolvedCertificate>
    fun certificateExpiry(domain: String): Pair<String, Long>?
    fun parentDomainCandidates(domain: String): List<String>
    fun resolveInstalledCertificateByName(certificateDomain: String): ResolvedCertificate?

}