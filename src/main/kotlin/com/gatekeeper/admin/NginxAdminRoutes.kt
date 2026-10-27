package com.gatekeeper.admin

import com.gatekeeper.feature.nginx.presentation.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Route.configureNginxAdminRoutes() {

    nginxCertificateAdminRoutes(get(), get())

    nginxManualConfigRoutes(
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get()
    )

    nginxSiteAdminRoutes(
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get()
    )

    nginxUpstreamRoutes(get(), get(), get(), get(), get(), get())

    nginxWizardAdminRoutes(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
}