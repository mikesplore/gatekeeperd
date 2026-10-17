package com.gatekeeper.plugins

import com.gatekeeper.customer.CustomerApplicationService
import com.gatekeeper.db.repositories.*
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.gate.GateService
import com.gatekeeper.nginx.NginxAdminService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.NginxCommandRunner
import com.gatekeeper.nginx.ExposedCertificateStore
import com.gatekeeper.config.AppConfig
import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.core.ports.FileStore
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.core.ports.TransactionRunner
import com.gatekeeper.infrastructure.ExposedTransactionRunner
import com.gatekeeper.infrastructure.LocalFileStore
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import com.gatekeeper.infrastructure.TcpPortProbe
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
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.audit.data.persistence.ExposedAuditRepository
import com.gatekeeper.feature.notification.domain.repository.NotificationRepository
import com.gatekeeper.feature.notification.data.persistence.ExposedNotificationRepository
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository
import com.gatekeeper.feature.certificate.data.persistence.ExposedCertificateRepository
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository as IntegrationOutboxRepositoryPort
import com.gatekeeper.feature.integrations.data.persistence.ExposedIntegrationOutboxRepository
import com.gatekeeper.feature.integrations.domain.repository.GitHubCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubInstallationStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubWebhookStore
import com.gatekeeper.feature.integrations.data.persistence.ExposedGitHubCredentialStore
import com.gatekeeper.feature.integrations.data.persistence.ExposedGitHubInstallationStore
import com.gatekeeper.feature.integrations.data.persistence.ExposedGitHubWebhookStore
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import com.gatekeeper.feature.integrations.data.persistence.ExposedProviderCredentialRepository
import com.gatekeeper.feature.integrations.data.persistence.ExposedRegistryCredentialRepository
import com.gatekeeper.feature.deployment.domain.repository.DeploymentPortAllocationRepository as DeploymentPortAllocationPort
import com.gatekeeper.feature.deployment.data.persistence.ExposedDeploymentPortAllocationRepository
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.data.persistence.ExposedEnvironmentSetRepository
import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import com.gatekeeper.feature.deployment.data.process.HostDeploymentCommandRunner
import com.gatekeeper.feature.deployment.domain.port.GitHubTokenProvider
import com.gatekeeper.deployment.SourceFetcher
import com.gatekeeper.deployment.ImageBuilder
import com.gatekeeper.deployment.DeploymentWorker
import com.gatekeeper.integrations.GitHubAppClient
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val applicationModule = module {
    single { AppSettings.fromEnv() }
    single { DockerService(get<AppSettings>()["DOCKER_SOCKET"] ?: AppConfig.dockerSocket) }
    single<DockerGateway> { get<DockerService>() }
    single { DockerCleanupService(get<DockerService>(), get<AuditRepository>()) }
    single<ProcessRunner> { ProcessRunnerImpl() }
    single<PortProbe> { TcpPortProbe() }
    single<FileStore> { LocalFileStore() }
    single<TransactionRunner> { ExposedTransactionRunner() }
    single { NginxCommandRunner(get<ProcessRunner>()) }
    single<NotificationRepository> { ExposedNotificationRepository() }
    single<AuditRepository> { ExposedAuditRepository(get()) }
    single<CertificateRepository> { ExposedCertificateRepository() }
    single<IntegrationOutboxRepositoryPort> { ExposedIntegrationOutboxRepository() }
    single<GitHubCredentialStore> { ExposedGitHubCredentialStore(get()) }
    single<GitHubInstallationStore> { ExposedGitHubInstallationStore() }
    single<GitHubWebhookStore> { ExposedGitHubWebhookStore() }
    single<ProviderCredentialStore> { get<ExposedProviderCredentialRepository>() }
    single { ExposedProviderCredentialRepository() }
    single<RegistryCredentialStore> { ExposedRegistryCredentialRepository(get<ProviderCredentialStore>()) }
    single { GitHubAppClient(get(), get()) }
    single<DeploymentPortAllocationPort> { ExposedDeploymentPortAllocationRepository() }
    single<EnvironmentSetStore> { ExposedEnvironmentSetRepository() }
    single<DeploymentCommandRunner> { HostDeploymentCommandRunner() }
    single<GitHubTokenProvider> { GitHubTokenProvider { get<GitHubAppClient>().installationToken() } }
    single { SourceFetcher(get<DeploymentCommandRunner>(), get<GitHubTokenProvider>()) }
    single { ImageBuilder(get<DeploymentCommandRunner>()) }
    single { DeploymentWorker(get(), get(), get(), get()) }
    single<ProjectQueryRepository> { ExposedProjectQueryRepository() }
    single<SupportRequestRepository> { ExposedSupportRequestRepository() }
    single { CustomerApplicationService(get<ProjectQueryRepository>(), get(), get(), get<SupportRequestRepository>()) }
    single { GateService(get<ProjectQueryRepository>(), get<GetLatestPaymentLink>(), get()) }
    single { GateApplicationService(get<ProjectQueryRepository>(), get<GateService>()) }
    single { NginxService.configured(get<NginxCommandRunner>()) }
    single { ExposedCertificateStore(get<CertificateRepository>()) }
    single { NginxAdminService(get(), get<ExposedCertificateStore>()) }
    single<PaymentDomainRepository> { ExposedPaymentRepository() }
    single { ProjectBalanceAdapter(get<PaymentDomainRepository>()) }
    single<PaymentEventDomainRepository> { ExposedPaymentEventRepository() }
    single { PaymentProjectAdapter() }
    single { PaymentServiceAdapter() }
    single { PaymentBalanceAdapter(get<PaymentDomainRepository>(), get(), get()) }
    single<PaymentEffects> { PaymentEffectsAdapter(get(), get()) }
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
