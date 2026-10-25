package com.gatekeeper.feature.nginx.presentation

sealed interface CertificateOperationResult<out T> {
    data class Success<T>(val value: T) : CertificateOperationResult<T>
    data object InvalidDomain : CertificateOperationResult<Nothing>
    data class InvalidEmail(val message: String) : CertificateOperationResult<Nothing>
    data object CertbotUnavailable : CertificateOperationResult<Nothing>
    data object Failed : CertificateOperationResult<Nothing>
    data object NotFound : CertificateOperationResult<Nothing>
    data object RenewalFailed : CertificateOperationResult<Nothing>
    data class InUse(val message: String) : CertificateOperationResult<Nothing>
}
