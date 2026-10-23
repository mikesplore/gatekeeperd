package com.gatekeeper.plugins

import com.gatekeeper.customer.CustomerApplicationService
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.customer.data.persistence.ExposedCustomerRepository
import com.gatekeeper.db.repositories.*
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.gate.GateService
import com.gatekeeper.nginx.NginxAdminService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.NginxCommandRunner
import com.gatekeeper.nginx.ExposedCertificateStore
import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.docker.DockerGatewayFactory
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.core.ports.FileStore
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.core.ports.HttpPortProbe
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.core.ports.TransactionRunner
import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.core.ports.DeploymentLock
import com.gatekeeper.infrastructure.ExposedTransactionRunner
import com.gatekeeper.infrastructure.LocalFileStore
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import com.gatekeeper.infrastructure.DistributedDeploymentLock
import com.gatekeeper.infrastructure.TcpPortProbe
import com.gatekeeper.infrastructure.TcpHttpPortProbe
import com.gatekeeper.infrastructure.TcpCutoverProbe
import com.gatekeeper.feature.payment.data.persistence.ExposedPaymentRepository
import com.gatekeeper.feature.payment.data.persistence.ExposedPaymentEventRepository
import com.gatekeeper.feature.payment.data.persistence.PaymentEffectsAdapter
import com.gatekeeper.feature.payment.data.persistence.PaymentProjectAdapter
import com.gatekeeper.feature.payment.data.persistence.PaymentBalanceAdapter
import com.gatekeeper.feature.payment.data.persistence.PaymentServiceAdapter
import com.gatekeeper.feature.payment.data.persistence.ExposedServiceMembershipRepository
import com.gatekeeper.feature.payment.domain.repository.ServiceMembershipRepository
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
import com.gatekeeper.feature.payment.domain.usecase.ListServicePayments
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.payment.domain.usecase.CompletePaystackCallback
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentsByProject
import com.gatekeeper.feature.payment.domain.usecase.GetLatestPaymentLink
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentDashboardData
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness
import com.gatekeeper.feature.payment.data.provider.PaystackGateway
import com.gatekeeper.feature.payment.data.provider.PaystackClient
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
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.data.persistence.ExposedDeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.deployment.data.persistence.ExposedServiceDomainRepository
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.data.persistence.ExposedEnvironmentSetRepository
import com.gatekeeper.deployment.DeploymentQueue
import com.gatekeeper.feature.deployment.data.persistence.ExposedDeploymentQueue
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import com.gatekeeper.feature.project.data.persistence.ExposedProjectAdjustmentRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository as ProjectStore
import com.gatekeeper.feature.project.data.persistence.ExposedProjectRepository
import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import com.gatekeeper.feature.deployment.data.process.HostDeploymentCommandRunner
import com.gatekeeper.feature.deployment.domain.port.GitHubTokenProvider
import com.gatekeeper.deployment.SourceFetcher
import com.gatekeeper.deployment.ImageBuilder
import com.gatekeeper.deployment.DeploymentWorker
import com.gatekeeper.deployment.DeploymentTriggerService
import com.gatekeeper.feature.service.data.persistence.ExposedServiceRepository
import com.gatekeeper.feature.service.data.persistence.ExposedSiteRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.GitHubAppClient
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.integrations.ScribedIntegrationClient
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val applicationModule = module {
    single { AppSettings.fromEnv() }
    single<ProcessRunner> { ProcessRunnerImpl() }
    single<DockerGateway> { DockerGatewayFactory.create(get<AppSettings>().docker, get<ProcessRunner>()) }
    single { DockerCleanupService(get<DockerGateway>(), get<AuditRepository>()) }
    single { DeploymentTriggerService(get<ProcessRunner>()) }
    single<PortProbe> { TcpPortProbe() }
    single<HttpPortProbe> { TcpHttpPortProbe() }
    single<CutoverProbe> { TcpCutoverProbe() }
    single<FileStore> { LocalFileStore() }
    single<TransactionRunner> { ExposedTransactionRunner() }
    single { NginxCommandRunner(get<ProcessRunner>()) }
    single<NotificationRepository> { ExposedNotificationRepository() }
    single<CustomerStore> { ExposedCustomerRepository() }
    single<AuditRepository> { ExposedAuditRepository(get()) }
    single<CertificateRepository> { ExposedCertificateRepository() }
    single<IntegrationOutboxRepositoryPort> { ExposedIntegrationOutboxRepository() }
    single<GitHubCredentialStore> { ExposedGitHubCredentialStore(get()) }
    single<GitHubInstallationStore> { ExposedGitHubInstallationStore() }
    single<GitHubWebhookStore> { ExposedGitHubWebhookStore() }
    single<ProviderCredentialStore> { get<ExposedProviderCredentialRepository>() }
    single { ExposedProviderCredentialRepository() }
    single<RegistryCredentialStore> { ExposedRegistryCredentialRepository(get<ProviderCredentialStore>()) }
    single { GitHubAppClient(get(), get(), get<AppSettings>().github) }
    single { ResendClient(get<AppSettings>().integrations) }
    single { ScribedIntegrationClient(get<AppSettings>().integrations, get<ProjectStore>()) }
    single<DeploymentPortAllocationPort> { ExposedDeploymentPortAllocationRepository() }
    single<EnvironmentSetStore> { ExposedEnvironmentSetRepository() }
    single<DeploymentJobRepository> { ExposedDeploymentJobRepository() }
    single<DeploymentQueue> { ExposedDeploymentQueue(get()) }
    single<ServiceDomainRepository> { ExposedServiceDomainRepository() }
    single<ProjectAdjustmentStore> { ExposedProjectAdjustmentRepository() }
    single<ProjectStore> { ExposedProjectRepository() }
    single<DeploymentCommandRunner> { HostDeploymentCommandRunner(get<ProcessRunner>()) }
    single<GitHubTokenProvider> { GitHubTokenProvider { get<GitHubAppClient>().installationToken() } }
    single { SourceFetcher(get<DeploymentCommandRunner>(), get<GitHubTokenProvider>()) }
    single { ImageBuilder(get<DeploymentCommandRunner>()) }
    single<DeploymentLock> { DistributedDeploymentLock() }
    single { DeploymentWorker(get(), get(), get(), get(), get(), get(), get(), get<AppSettings>().scheduler, get<AppSettings>().nginx, get(), get(), get(), get(), get()) }
    single<ProjectQueryRepository> { ExposedProjectQueryRepository(get<ProjectStore>()) }
    single<SupportRequestRepository> { ExposedSupportRequestRepository() }
    single { CustomerApplicationService(get<ProjectQueryRepository>(), get(), get(), get<SupportRequestRepository>()) }
    single { GateService(get<ProjectQueryRepository>(), get<GetLatestPaymentLink>(), get(), get<AppSettings>().application) }
    single { GateApplicationService(get<ProjectQueryRepository>(), get<GateService>()) }
    single { NginxService.configured(get<AppSettings>().nginx, get<NginxCommandRunner>(), get<PortProbe>(), get<CutoverProbe>()) }
    single { ExposedCertificateStore(get<CertificateRepository>()) }
    single { NginxAdminService(get(), get<ExposedCertificateStore>(), get<AppSettings>().nginx.sslCertPath) }
    single<PaymentDomainRepository> { ExposedPaymentRepository() }
    single { ProjectBalanceAdapter(get<PaymentDomainRepository>(), get<ProjectAdjustmentStore>(), get(), get<ProjectStore>()) }
    single<PaymentEventDomainRepository> { ExposedPaymentEventRepository() }
    single { PaymentProjectAdapter(get<ProjectStore>()) }
    single { PaymentServiceAdapter() }
    single<ServiceMembershipRepository> { ExposedServiceMembershipRepository() }
    single { PaymentBalanceAdapter(get<PaymentDomainRepository>(), get(), get(), get()) }
    single<PaymentEffects> { PaymentEffectsAdapter(get(), get(), get(), get(), get<ProjectStore>()) }
    single { ApplyWebhookEvent(get(), get()) }
    single { PaystackClient(get<AppSettings>().payment) }
    single<PaymentGateway>(named("paystackGateway")) { PaystackGateway(get<AppSettings>().payment, get<AppSettings>().integrations, get()) }
    single<MpesaGateway> { MpesaGateway(get<AppSettings>().payment) }
    single<PaymentGateway>(named("mpesaGateway")) { get<MpesaGateway>() }
    single<PaymentProviderReadiness> { PaymentProviderReadinessAdapter(get<AppSettings>().payment, get()) }
    single { GetPaymentMethodAvailability(get()) }
    single { ListPayments(get()) }
    single { ListProjectPayments(get<PaymentProjectAdapter>(), get<PaymentDomainRepository>()) }
    single { ListServicePayments(get<PaymentDomainRepository>()) }
    single { GetPayment(get<PaymentDomainRepository>()) }
    single { ListPaymentsByProject(get<PaymentDomainRepository>()) }
    single { GetLatestPaymentLink(get<PaymentDomainRepository>()) }
    single { GetPaymentDashboardData(get<PaymentDomainRepository>()) }
    single { ReconcilePayment(get<PaymentDomainRepository>(), get()) }
    single { RecordCashPayment(get<PaymentProjectAdapter>(), get<PaymentDomainRepository>(), get()) }
    single<PaymentCurrencyPort> { PaymentCurrencyPort { get<ProjectStore>().findAll().firstOrNull()?.currency } }
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
    single<ServiceRepository> { ExposedServiceRepository(get()) }
    single<SiteRepository> { ExposedSiteRepository() }
}

fun Application.configureDependencyInjection() {
    install(Koin) {
        modules(applicationModule)
    }
}
