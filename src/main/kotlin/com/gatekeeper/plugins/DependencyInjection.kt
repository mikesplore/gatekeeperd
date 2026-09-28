package com.gatekeeper.plugins

import com.gatekeeper.customer.CustomerApplicationService
import com.gatekeeper.db.repositories.*
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.gate.GateService
import com.gatekeeper.nginx.NginxAdminService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.feature.payment.data.persistence.ExposedPaymentRepository
import com.gatekeeper.feature.payment.data.persistence.ExposedPaymentEventRepository
import com.gatekeeper.feature.payment.data.persistence.PaymentEffectsAdapter
import com.gatekeeper.feature.payment.data.persistence.PaymentProjectAdapter
import com.gatekeeper.feature.payment.data.persistence.PaymentBalanceAdapter
import com.gatekeeper.feature.payment.data.provider.PaymentProviderReadinessAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository as PaymentEventDomainRepository
import com.gatekeeper.feature.payment.domain.usecase.ApplyWebhookEvent
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent
import com.gatekeeper.feature.payment.domain.usecase.VerifyPayment
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentEvents
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness
import com.gatekeeper.paystack.PaystackProviderClient
import com.gatekeeper.mpesa.MpesaClient
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val applicationModule = module {
    single<ProjectQueryRepository> { ExposedProjectQueryRepository() }
    single<SupportRequestRepository> { ExposedSupportRequestRepository() }
    single { CustomerApplicationService(get<ProjectQueryRepository>(), get<PaymentDomainRepository>(), get<SupportRequestRepository>()) }
    single { GateService(get<PaymentDomainRepository>()) }
    single { GateApplicationService(get<ProjectQueryRepository>(), get<GateService>()) }
    single { NginxService() }
    single { NginxAdminService(get()) }
    single<PaymentDomainRepository> { ExposedPaymentRepository() }
    single<PaymentEventDomainRepository> { ExposedPaymentEventRepository() }
    single { PaymentProjectAdapter() }
    single { PaymentBalanceAdapter(get<PaymentDomainRepository>(), get()) }
    single<PaymentEffects> { PaymentEffectsAdapter() }
    single { ApplyWebhookEvent(get(), get()) }
    single(named("paystackGateway")) { PaystackProviderClient() }
    single(named("mpesaGateway")) { MpesaClient }
    single<PaymentProviderReadiness> { PaymentProviderReadinessAdapter() }
    single { GetPaymentMethodAvailability(get()) }
    single { InitiatePayment(get(), get<PaymentProjectAdapter>(), get<PaymentBalanceAdapter>(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway")))) }
    single { ProcessPaymentEvent(get(), get(), get<PaymentProjectAdapter>(), get()) }
    single { ListPaymentEvents(get()) }
    single { VerifyPayment(mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
    single { ReconcilePayments(get(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
}

fun Application.configureDependencyInjection() {
    install(Koin) {
        modules(applicationModule)
    }
}
