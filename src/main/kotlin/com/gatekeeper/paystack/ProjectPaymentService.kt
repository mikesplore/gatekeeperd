package com.gatekeeper.paystack

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository

object ProjectPaymentService {

    fun isPaystackConfigured(): Boolean = AppConfig.paystackSecretKey.isNotBlank()

    suspend fun initializeForProject(
        project: ProjectRepository.ProjectRecord,
        emailOverride: String? = null
    ): Result<String> {
        if (!isPaystackConfigured()) {
            return Result.failure(IllegalStateException("Paystack is not configured on this server"))
        }

        val email = emailOverride?.trim()?.takeIf { it.isNotBlank() } ?: project.clientEmail?.trim()
        if (email.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No client email configured for this project"))
        }

        val amount = project.amountDue
            ?: return Result.failure(IllegalStateException("No amount due configured for this project"))

        val publicBase = AppConfig.publicBaseUrl.trim().trimEnd('/')
        if (publicBase.isBlank()) {
            return Result.failure(IllegalStateException("GATEKEEPER_PUBLIC_URL is not configured"))
        }

        val callbackUrl = "$publicBase/api/gate/payment/callback?project=${project.slug}"

        return PaystackClient.initializePayment(
            email = email,
            amountNaira = amount,
            projectSlug = project.slug,
            currency = project.currency,
            callbackUrl = callbackUrl
        )
    }
}
