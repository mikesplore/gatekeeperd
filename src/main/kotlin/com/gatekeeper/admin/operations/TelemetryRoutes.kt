package com.gatekeeper.admin.operations


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.plugins.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.util.*

fun Route.registerTelemetryRoutes(dependencies: OperationsAdminDependencies) {

    get("/api/admin/metrics") {
        call.respond(Metrics.snapshot())
    }
}
