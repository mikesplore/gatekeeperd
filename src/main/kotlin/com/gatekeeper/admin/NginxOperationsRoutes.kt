package com.gatekeeper.admin

import io.ktor.server.routing.Route

fun Route.registerNginxOperationsRoutes(dependencies: OperationsAdminDependencies) {
    registerSiteDashboardRoutes(dependencies)
    registerNginxConfigRoutes(dependencies)
}
