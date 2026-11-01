package com.gatekeeper.admin.operations


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import io.ktor.server.routing.Route

fun Route.registerNginxOperationsRoutes(dependencies: OperationsAdminDependencies) {
    registerSiteDashboardRoutes(dependencies)
    registerNginxConfigRoutes(dependencies)
}
