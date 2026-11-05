package com.gatekeeper.feature.service.data.integration

import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceIntegrationPort

class GatekeeperOnlyInvoiceAdapter : ServiceInvoiceIntegrationPort {
    override suspend fun findLegacyInvoice(serviceId: java.util.UUID): ServiceInvoiceIntegrationPort.LegacyInvoice? = null

    override suspend fun ensureDocument(
        project: com.gatekeeper.feature.project.domain.model.ProjectRecord,
        serviceId: java.util.UUID,
        serviceName: String,
        description: String,
        amount: java.math.BigDecimal
    ): Boolean {
        // Gatekeeper owns invoice state; document rendering is requested by the download endpoint.
        return true
    }
}
