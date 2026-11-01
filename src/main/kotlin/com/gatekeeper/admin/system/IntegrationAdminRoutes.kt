package com.gatekeeper.admin.system


import com.gatekeeper.admin.application.*
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureIntegrationAdminRoutes() {
    val service = IntegrationAdminApplicationService(
        get<IntegrationOutboxRepository>(), get<ScribedIntegrationClient>(),
        get<PaymentRepository>(), get<ProjectBalanceAdapter>()
    )
    routing {
        authenticate("auth-admin") {
            get("/api/admin/integrations/outbox") { call.respond(service.pendingEvents()) }
            post("/api/admin/integrations/outbox/{id}/replay") {
                call.respond(service.replay(call.parameters["id"]))
            }
        }
    }
}
