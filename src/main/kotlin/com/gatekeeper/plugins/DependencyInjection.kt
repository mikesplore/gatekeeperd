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
import com.gatekeeper.feature.payment.data.persistence.PaymentServiceAdapter
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.data.provider.PaymentProviderReadinessAdapter
import com.gatekeeper.feature.payment.data.provider.JsonMpesaCallbackDecoder
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository as PaymentEventDomainRepository
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.usecase.ApplyWebhookEvent
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent
import com.gatekeeper.feature.payment.domain.usecase.HandlePaystackWebhook
import com.gatekeeper.feature.payment.domain.usecase.ReplayPaystackEvent
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentEventForReplay
import com.gatekeeper.feature.payment.domain.usecase.VerifyPayment
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentEvents
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.feature.payment.domain.usecase.HandleMpesaCallback
import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackDecoder
import com.gatekeeper.feature.payment.domain.usecase.ListPayments
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayment
import com.gatekeeper.feature.payment.domain.usecase.RecordCashPayment
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentRevenue
import com.gatekeeper.feature.payment.domain.usecase.PaymentCurrencyPort
import com.gatekeeper.feature.payment.domain.usecase.ListProjectPayments
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.payment.domain.usecase.CompletePaystackCallback
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentsByProject
import com.gatekeeper.feature.payment.domain.usecase.GetLatestPaymentLink
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentDashboardData
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness
import com.gatekeeper.feature.payment.data.provider.PaystackGateway
import com.gatekeeper.feature.payment.data.provider.MpesaGateway
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val applicationModule = module {
    single<ProjectQueryRepository> { ExposedProjectQueryRepository() }
    single<SupportRequestRepository> { ExposedSupportRequestRepository() }
    single { CustomerApplicationService(get<ProjectQueryRepository>(), get(), get(), get<SupportRequestRepository>()) }
    single { GateService(get<ProjectQueryRepository>(), get<GetLatestPaymentLink>(), get()) }
    single { GateApplicationService(get<ProjectQueryRepository>(), get<GateService>()) }
    single { NginxService() }
    single { NginxAdminService(get()) }
    single<PaymentDomainRepository> { ExposedPaymentRepository() }
    single { ProjectBalanceAdapter(get<PaymentDomainRepository>()) }
    single<PaymentEventDomainRepository> { ExposedPaymentEventRepository() }
    single { PaymentProjectAdapter() }
    single { PaymentServiceAdapter() }
    single { PaymentBalanceAdapter(get<PaymentDomainRepository>(), get(), get()) }
    single<PaymentEffects> { PaymentEffectsAdapter(get()) }
    single { ApplyWebhookEvent(get(), get()) }
    single<PaymentGateway>(named("paystackGateway")) { PaystackGateway() }
    single<PaymentGateway>(named("mpesaGateway")) { MpesaGateway }
    single<PaymentProviderReadiness> { PaymentProviderReadinessAdapter() }
    single { GetPaymentMethodAvailability(get()) }
    single { ListPayments(get()) }
    single { ListProjectPayments(get<PaymentProjectAdapter>(), get<PaymentDomainRepository>()) }
    single { GetPayment(get<PaymentDomainRepository>()) }
    single { ListPaymentsByProject(get<PaymentDomainRepository>()) }
    single { GetLatestPaymentLink(get<PaymentDomainRepository>()) }
    single { GetPaymentDashboardData(get<PaymentDomainRepository>()) }
    single { ReconcilePayment(get<PaymentDomainRepository>(), get()) }
    single { RecordCashPayment(get<PaymentProjectAdapter>(), get<PaymentDomainRepository>(), get()) }
    single<PaymentCurrencyPort> { PaymentCurrencyPort { ProjectRepository.findAll().firstOrNull()?.currency } }
    single { GetPaymentRevenue(get<PaymentDomainRepository>(), get()) }
    single { InitiatePayment(get(), get<PaymentProjectAdapter>(), get<PaymentBalanceAdapter>(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get<PaymentServiceAdapter>()) }
    single { ProcessPaymentEvent(get(), get(), get<PaymentProjectAdapter>(), get()) }
    single { HandlePaystackWebhook(get()) }
    single { ReplayPaystackEvent(get(), get()) }
    single { GetPaymentEventForReplay(get()) }
    single<MpesaCallbackDecoder> { JsonMpesaCallbackDecoder() }
    single { HandleMpesaCallback(get(), get()) }
    single { ListPaymentEvents(get()) }
    single { VerifyPayment(mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
    single { CompletePaystackCallback(get<PaymentProjectAdapter>(), get()) }
    single { ReconcilePayments(get(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
}

fun Application.configureDependencyInjection() {
    install(Koin) {
        modules(applicationModule)
    }
}
