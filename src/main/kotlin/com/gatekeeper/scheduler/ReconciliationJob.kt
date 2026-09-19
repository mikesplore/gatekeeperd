package com.gatekeeper.scheduler

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.paystack.PaystackClient
import com.gatekeeper.paystack.PaymentService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*

object ReconciliationJob {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.ReconciliationJob")

    fun start(scope: CoroutineScope) {
        scope.launch {
            delay(1.minutes)
            while (isActive) {
                runCatching {
                    reconcilePendingPayments()
                }.onFailure {
                    logger.error("ReconciliationJob run failed", it)
                }
                delay(AppConfig.reconciliationIntervalMinutes.minutes)
            }
        }
    }

    private suspend fun reconcilePendingPayments() {
        val stale = PaymentRepository.findPendingOlderThan(AppConfig.reconciliationStaleMinutes)
        if (stale.isEmpty()) return

        logger.info("Reconciliation: checking ${stale.size} stale pending payment(s)")

        stale.forEach { payment ->
            val verification = PaystackClient.verifyTransaction(payment.paystackReference).getOrNull()
                ?: run {
                    logger.warn("Reconciliation verify failed for ref=${payment.paystackReference}")
                    return@forEach
                }

            when (verification.status.lowercase()) {
                "success" -> {
                    val project = ProjectRepository.findById(payment.projectId) ?: return@forEach
                    val amountNaira = verification.amount?.let { koboToNaira(it) } ?: payment.amount
                    PaymentService.applySuccessfulPayment(
                        reference = payment.paystackReference,
                        projectSlug = project.slug,
                        amountNaira = amountNaira,
                        currency = verification.currency,
                        verifiedVia = "reconciliation",
                        paidAt = LocalDateTime.now()
                    )
                }
                "failed" -> {
                    PaymentRepository.markGatewayStatusById(payment.id, "failed", "reconciliation")
                }
                "abandoned" -> {
                    PaymentRepository.markGatewayStatusById(payment.id, "abandoned", "reconciliation")
                }
                else -> {
                    logger.debug(
                        "Reconciliation: still pending at Paystack ref=${payment.paystackReference} status=${verification.status}"
                    )
                }
            }
        }
    }

    private fun koboToNaira(amountKobo: Long): BigDecimal =
        BigDecimal.valueOf(amountKobo).movePointLeft(2)
}
