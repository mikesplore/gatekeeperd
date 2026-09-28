package com.gatekeeper.feature.payment.domain.usecase

class CompletePaystackCallback(private val projects: PaymentProjectPort, private val verifyPayment: VerifyPayment) {
    suspend operator fun invoke(projectSlug: String, reference: String): Result<Completion> = runCatching {
        if (projectSlug.isBlank() || reference.isBlank()) fail("invalid_callback", "Missing project or payment reference", FailureKind.INVALID_REQUEST)
        val project = projects.find(projectSlug) ?: fail("project_not_found", "Project not found", FailureKind.NOT_FOUND)
        val verification = verifyPayment(VerifyPayment.Command("paystack", reference, project.id, "callback")).getOrElse {
            fail("payment_verification_failed", it.message ?: "Unable to verify payment", FailureKind.PROVIDER_UNAVAILABLE)
        }
        Completion(project.slug, project.domain.orEmpty(), verification.success)
    }

    private fun fail(code: String, message: String, kind: FailureKind): Nothing = throw CallbackFailure(code, message, kind)

    enum class FailureKind { NOT_FOUND, INVALID_REQUEST, PROVIDER_UNAVAILABLE }
    class CallbackFailure(val code: String, message: String, val kind: FailureKind) : RuntimeException(message)

    data class Completion(val projectSlug: String, val projectDomain: String, val successful: Boolean)
}
