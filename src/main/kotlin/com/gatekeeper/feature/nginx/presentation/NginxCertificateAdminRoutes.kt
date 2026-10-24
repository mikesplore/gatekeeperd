package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.nginx.CertificateOperationResult
import com.gatekeeper.nginx.NginxService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.nginxCertificateAdminRoutes(
    nginxService: NginxService,
    certificateProvisioner: CertificateProvisioner
){
    get("/api/admin/nginx/certificate/list") {
        val certificateManagerIssue = nginxService.certificateManagerIssue()
        call.respond(
            certificateProvisioner.listCertificates().copy(
                managerReady = certificateManagerIssue == null,
                managerMessage = certificateManagerIssue
            )
        )
    }

    post("/api/admin/nginx/certificate/install") {
        val body = try {
            call.receive<CertificateInstallRequest>()
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
            return@post
        }

        when (val result = nginxAdminService.installCertificate(body.domain, body.email)) {
            is CertificateOperationResult.Success -> call.respond(result.value)
            CertificateOperationResult.InvalidDomain -> call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_domain",
                "Invalid domain"
            )

            is CertificateOperationResult.InvalidEmail -> call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_email",
                result.message
            )

            CertificateOperationResult.CertbotUnavailable -> call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "certificate_manager_unavailable",
                "Certbot is unavailable"
            )

            CertificateOperationResult.Failed -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_error",
                "Failed to install SSL certificate"
            )

            else -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_error",
                "Certificate installation failed"
            )
        }
    }

    post("/api/admin/nginx/certificate/remove/{domain}") {
        val domain = call.parameters["domain"]
        if (domain == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
            return@post
        }

        when (val result = nginxAdminService.removeCertificate(domain)) {
            is CertificateOperationResult.Success -> call.respond(result.value)
            is CertificateOperationResult.InUse -> call.respondError(
                HttpStatusCode.Conflict,
                "certificate_in_use",
                result.message
            )

            CertificateOperationResult.InvalidDomain -> call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_domain",
                "Invalid domain"
            )

            CertificateOperationResult.CertbotUnavailable -> call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "certificate_manager_unavailable",
                "Certbot is unavailable"
            )

            else -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_error",
                "Failed to remove SSL certificate"
            )
        }
    }

    post("/api/admin/nginx/certificate/renew/{domain}") {
        val domain = call.parameters["domain"]
        if (domain == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
            return@post
        }
        when (val result = nginxAdminService.renewCertificate(domain)) {
            is CertificateOperationResult.Success -> call.respond(result.value)
            CertificateOperationResult.InvalidDomain -> call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_domain",
                "Invalid domain"
            )

            CertificateOperationResult.NotFound -> call.respondError(
                HttpStatusCode.NotFound,
                "certificate_not_found",
                "No installed certificate found for '$domain'"
            )

            CertificateOperationResult.CertbotUnavailable -> call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "certificate_manager_unavailable",
                "Certbot is unavailable"
            )

            CertificateOperationResult.RenewalFailed -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_renewal_failed",
                "Certbot renewal failed or exceeded the 8-minute limit. Check Gatekeeperd logs for Certbot output and nginx reload status."
            )

            else -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_error",
                "Certificate renewal failed"
            )
        }
    }

    get("/api/admin/nginx/certificate/status/{domain}") {
        val domain = call.parameters["domain"]
        if (domain == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
            return@get
        }

        when (val result = nginxAdminService.certificateStatus(domain)) {
            is CertificateOperationResult.Success -> call.respond(result.value)
            CertificateOperationResult.InvalidDomain -> call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_domain",
                "Invalid domain"
            )

            else -> call.respondError(
                HttpStatusCode.InternalServerError,
                "certificate_error",
                "Unable to check certificate"
            )
        }
    }
}