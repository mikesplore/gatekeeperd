package com.gatekeeper.plugins

import com.gatekeeper.customer.CustomerApplicationService
import com.gatekeeper.customer.CustomerSupportAccessService
import com.gatekeeper.auth.AuthUserRepository
import com.gatekeeper.auth.ExposedAuthUserRepository
import com.gatekeeper.rbac.domain.RbacRepository
import com.gatekeeper.rbac.data.ExposedRbacRepository
import com.gatekeeper.rbac.application.RbacApplicationService
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.customer.data.persistence.ExposedCustomerRepository
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.gate.GateService
import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.docker.DockerGatewayFactory
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.docker.DockerAdminApplicationService
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
import com.gatekeeper.feature.payment.data.persistence.PaymentEligibilityAdapter
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
import com.gatekeeper.feature.payment.domain.usecase.PaymentServicePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentEligibilityPort
import com.gatekeeper.feature.payment.domain.usecase.MpesaInitiationGuard
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
import com.gatekeeper.feature.payment.presentation.PaymentAdminApplicationService
import com.gatekeeper.feature.payment.presentation.MpesaPaymentProtection
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
import com.gatekeeper.feature.customer.data.persistence.ExposedSupportRequestRepository
import com.gatekeeper.feature.customer.domain.repository.SupportRequestRepository
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ClassifyConfigsUseCase
import com.gatekeeper.feature.nginx.application.ConvertSiteToDeploymentManagedUseCase
import com.gatekeeper.feature.nginx.application.DeleteManualConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableManualConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableProjectUseCase
import com.gatekeeper.feature.nginx.application.InspectManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.project.data.persistence.ExposedProjectQueryRepository
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import com.gatekeeper.feature.service.data.persistence.ExposedServiceRepository
import com.gatekeeper.feature.service.data.persistence.ExposedServiceInvoiceRepository
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import com.gatekeeper.feature.service.data.persistence.ExposedSiteRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.application.RecoverInterruptedCutoverUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.RepairDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.application.RollbackSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.application.UpdateSiteConfigBlockUseCase
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.port.PortProbe as NginxPortProbe
import com.gatekeeper.feature.nginx.domain.service.NginxConfigParser
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.domain.service.UpstreamValidation
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.infrastructure.NginxSiteFileStore
import com.gatekeeper.feature.nginx.infrastructure.RenderModelFromSite
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.presentation.PlanNginxEnableRequest
import com.gatekeeper.integrations.GitHubAppClient
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.integrations.GitHubWebhookApplicationService
import com.gatekeeper.integrations.GitHubAdminApplicationService
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.NginxSiteReconciliationService
import com.gatekeeper.feature.nginx.infrastructure.ExposedNginxCertificateStore
import com.gatekeeper.feature.nginx.domain.port.NginxCertificateStore
import com.gatekeeper.feature.nginx.application.NginxAdministrationService
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy
import com.gatekeeper.scheduler.AutoBlockerJob
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val applicationModule = module {
    single<AuthUserRepository> { ExposedAuthUserRepository() }
    single<RbacRepository> { ExposedRbacRepository() }
    single { RbacApplicationService(get()) }
    single { AppSettings.fromEnv() }
    single<AppSettings.NginxSettings> { get<AppSettings>().nginx }
    single<AppSettings.SchedulerSettings> { get<AppSettings>().scheduler }
    single<ProcessRunner> { ProcessRunnerImpl() }
    single<DockerGateway> { DockerGatewayFactory.create(get<AppSettings>().docker, get<ProcessRunner>()) }
    single { DockerAdminApplicationService(get<DockerGateway>(), get<AppSettings>().docker) }
    single { HealthApplicationService() }
    single { GitHubWebhookApplicationService(get<AppSettings>(), get<GitHubCredentialStore>(), get<GitHubWebhookStore>(), get<DeploymentJobRepository>(), get<AuditRepository>()) }
    single { GitHubAdminApplicationService(get<AppSettings>(), get<GitHubCredentialStore>(), get<GitHubInstallationStore>(), get<GitHubAppClient>(), get<AuditRepository>()) }
    single { DockerCleanupService(get<DockerGateway>(), get<AuditRepository>(), setOf(get<AppSettings>().docker.internalNetwork)) }
    single { DeploymentTriggerService(get<ProcessRunner>()) }
    single<PortProbe> { TcpPortProbe() }
    single<NginxPortProbe> { NginxPortProbe(get<PortProbe>()) }
    single<HttpPortProbe> { TcpHttpPortProbe() }
    single<CutoverProbe> { TcpCutoverProbe(get()) }
    single<FileStore> { LocalFileStore() }
    single<TransactionRunner> { ExposedTransactionRunner() }
    single { NginxEnvironmentPolicy(
        environment = get<AppSettings.NginxSettings>().environment,
        nginxHostSudoEnabled = get<AppSettings.NginxSettings>().hostOperationsEnabled,
        nginxCertbotEnabled = get<AppSettings.NginxSettings>().certbotEnabled,
        customCertDirectory = get<AppSettings.NginxSettings>().customCertDirectory,
        nginxBinary = get<AppSettings.NginxSettings>().nginxBinary,
        developmentConfigPath = get<AppSettings.NginxSettings>().developmentConfigPath
    ) }
    single<NginxCommandRunner> { NginxCommandRunner(get<ProcessRunner>(), get<NginxEnvironmentPolicy>()) }
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
    single { CustomerSupportAccessService(get<CustomerStore>(), get<ProjectStore>(), get<ResendClient>(), get<AppSettings>().auth) }
    single { ScribedIntegrationClient(get<AppSettings>().integrations, get<ProviderCredentialStore>()) }
    single<DeploymentPortAllocationPort> { ExposedDeploymentPortAllocationRepository() }
    single<EnvironmentSetStore> { ExposedEnvironmentSetRepository() }
    single<DeploymentJobRepository> { ExposedDeploymentJobRepository() }
    single<DeploymentQueue> { ExposedDeploymentQueue(get()) }
    single<ServiceDomainRepository> { ExposedServiceDomainRepository() }
    single<ProjectAdjustmentStore> { ExposedProjectAdjustmentRepository() }
    single<ProjectStore> { ExposedProjectRepository(get()) }
    single<DeploymentCommandRunner> { HostDeploymentCommandRunner(get<ProcessRunner>()) }
    single<GitHubTokenProvider> { GitHubTokenProvider { get<GitHubAppClient>().installationToken() } }
    single { SourceFetcher(get<DeploymentCommandRunner>(), get<GitHubTokenProvider>()) }
    single { ImageBuilder(get<DeploymentCommandRunner>()) }
    single<DeploymentLock> { DistributedDeploymentLock() }
    single { DeploymentWorker(get(), get(),get(),get(),get(), get(),get(), get(), get<AppSettings>().deploymentWorker, get(), get(),get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<ProjectQueryRepository> { ExposedProjectQueryRepository(get<ProjectStore>(), get(), get()) }
    single<SupportRequestRepository> { ExposedSupportRequestRepository() }
    single { CustomerApplicationService(get<ProjectQueryRepository>(), get(), get(), get<SupportRequestRepository>()) }
    single { PaymentAdminApplicationService(get<RecordCashPayment>()) }
    single { GateApplicationService(get<ProjectQueryRepository>(), get<GateService>(), get<ServiceRepository>(), get()) }
    single<NginxCertificateStore> { ExposedNginxCertificateStore(get(), get()) }
    single { NginxAdministrationService(get(), get<AppSettings>().nginx.sslCertPath, get(), get(), get(), get(), get(), get(), get()) }
    single { NginxValidationService() }
    single { UpstreamValidation() }
    single { NginxConfigParser() }
    single { NginxSelfDomainExclusion(get<AppSettings>().nginx.selfDomains) }
    single { DomainRules(get(), get<SiteRepository>()) }
    single<NginxSiteStorage> {
        NginxSiteFileStore(
            settings = get<AppSettings>().nginx,
            nginxDomainRules = get(),
            nginxTestRunner = { get<NginxCommandRunner>().test() },
            nginxReloadRunner = { get<NginxCommandRunner>().reload() },
            removeSiteConfigUseCase = lazy { get<RemoveSiteConfigUseCase>() },
            listConfigArtifacts = lazy { get<ListConfigArtifactsUseCase>() },
            applySiteConfigUseCase = lazy { get<ApplySiteConfigUseCase>() },
            disableManConfig = lazy { get<DisableManualConfigUseCase>() }
        )
    }
    single {
        NginxConfigRenderer(
            gatekeeperUpstream = get<AppSettings>().nginx.gatekeeperAuthUpstream,
            sslCertPath = get<AppSettings>().nginx.sslCertPath,
            upstreamIncludeDirectory = get<AppSettings>().nginx.upstreamIncludeDirectory,
            nginxValidation = get()
        )
    }
    single { NginxConfigRendererService(get<NginxConfigRenderer>()) }
    single { CertbotCertificateProvisioner(get(), get<AppSettings>().nginx.sslCertPath, get(), get(), get(), get()) }
    single<CertificateProvisioner> { get<CertbotCertificateProvisioner>() }
    single { RenderModelFromSite(get(), get()) }
    single {
        PlanNginxEnableRequest(
            dockerService = get<DockerGateway>(),
            parser = get(),
            portProbe = get(),
            validator = get(),
            certificateProvisioner = get()
        )
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        ClassifyConfigsUseCase(
            nginxSiteStorage = get(),
            selfDomainExclusion = get(),
            sitesEnabledPath = nginxSettings.sitesEnabledPath,
            sitesAvailablePath = nginxSettings.sitesAvailablePath,
            configAnalyzer = lazy { get<NginxConflictAnalyzer>() }
        )
    }
    single { NginxConflictAnalyzer(get(), get(), get(), get()) }
    single {
        val nginxSettings = get<AppSettings>().nginx
        InspectSiteConfigUseCase(
            siteRepository = get(),
            upstreamIncludeDirectory = nginxSettings.upstreamIncludeDirectory,
            domainRules = get(),
            nginx = get(),
            parser = get(),
            nginxConfigDumpRunner = { get<NginxCommandRunner>().dumpConfig() },
            sitesAvailablePath = nginxSettings.sitesAvailablePath,
            sitesEnabledPath = nginxSettings.sitesEnabledPath
        )
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        ListConfigArtifactsUseCase(
            sitesAvailablePath = nginxSettings.sitesAvailablePath,
            sitesEnabledPath = nginxSettings.sitesEnabledPath,
            domainRules = get(),
            nginxConflictAnalyzer = get()
        )
    }
    single {
        ApplySiteConfigUseCase(
            commandRunner = get(),
            sitesAvailablePath = get<AppSettings>().nginx.sitesAvailablePath,
            sitesEnabledPath = get<AppSettings>().nginx.sitesEnabledPath,
            nginxSiteStorage = get(),
            selfDomainExclusion = get(),
            domainRules = get(),
            nginxConflictAnalyzer = get()
        )
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        RemoveSiteConfigUseCase(nginxSettings.sitesAvailablePath, nginxSettings.sitesEnabledPath, get())
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        DisableProjectUseCase(get(), nginxSettings.sitesAvailablePath, nginxSettings.sitesEnabledPath)
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        DisableManualConfigUseCase(
            get(), get(), get(), get(), { get<NginxCommandRunner>().reload() },
            nginxSettings.sitesAvailablePath, nginxSettings.sitesEnabledPath
        )
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        InspectManualConfigUseCase(nginxSettings.sitesAvailablePath, nginxSettings.sitesEnabledPath, get(), get())
    }
    single {
        val nginxSettings = get<AppSettings>().nginx
        DeleteManualConfigUseCase(get(), get(), get(), nginxSettings.sitesAvailablePath)
    }
    single {
        UpdateSiteConfigBlockUseCase(
            nginxSiteStorage = get(),
            inspectSite = get(),
            applySiteConfigUseCase = get()
        )
    }
    single {
        RollbackSiteConfigUseCase(
            nginxSiteStorage = get(),
            domainRules = get(),
            nginxSelfDomainExclusion = get(),
            applySiteConfigUseCase = get()
        )
    }
    single {
        SwitchDeploymentUpstreamUseCase(
            storage = get(),
            commandRunner = get(),
            cutoverProbe = get(),
            siteRepository = get(),
            renderer = get(),
            certificateProvisioner = get(),
            upstreamIncludeDirectory = get<AppSettings>().nginx.upstreamIncludeDirectory,
            applySiteConfigUseCase = get(),
            inspectSite = get(),
            nginxTestRunner = { get<NginxCommandRunner>().test() },
            nginxReloadRunner = { get<NginxCommandRunner>().reload() }
        )
    }
    single {
        ConvertSiteToDeploymentManagedUseCase(
            upstreamIncludeDirectory = get<AppSettings>().nginx.upstreamIncludeDirectory,
            siteConfig = get(),
            renderer = get(),
            cutoverProbe = get(),
            nginxSiteStorage = get(),
            inspectSite = get(),
            siteRepository = get(),
            certificateProvisioner = get(),
            verifyLocalRoute = !get<NginxEnvironmentPolicy>().isDev,
            nginxTestRunner = { get<NginxCommandRunner>().test() }
        )
    }
    single {
        RecoverInterruptedCutoverUseCase(
            upstreamIncludeDirectory = get<AppSettings>().nginx.upstreamIncludeDirectory,
            nginxSiteStorage = get(),
            convertSiteToDeploymentManagedUseCase = get(),
            localProbeRunner = { domain, tls -> get<CutoverProbe>().throughLocalNginx(domain, tls) },
            siteRepository = get(),
            nginxTestRunner = { get<NginxCommandRunner>().test() },
            nginxReloadRunner = { get<NginxCommandRunner>().reload() }
        )
    }
    single {
        RepairDeploymentUpstreamUseCase(
            upstreamIncludeDirectory = get<AppSettings>().nginx.upstreamIncludeDirectory,
            nginxStorage = get(),
            localProbeRunner = { domain, tls -> get<CutoverProbe>().throughLocalNginx(domain, tls) },
            nginxReloadRunner = { get<NginxCommandRunner>().reload() },
            nginxTestRunner = { get<NginxCommandRunner>().test() },
        )
    }
    single<PaymentDomainRepository> { ExposedPaymentRepository() }
    single<ServiceInvoiceRepository> { ExposedServiceInvoiceRepository() }
    single { ServiceInvoiceApplicationService(get(), get<PaymentDomainRepository>(), get<ProjectAdjustmentStore>()) }
    single { ProjectBalanceAdapter(get<PaymentDomainRepository>(), get<ProjectAdjustmentStore>(), get(), get()) }
    single<PaymentEventDomainRepository> { ExposedPaymentEventRepository() }
    single { PaymentProjectAdapter(get<ProjectStore>(), get()) }
    single<PaymentServicePort> { PaymentServiceAdapter() }
    single<com.gatekeeper.feature.payment.domain.usecase.PaymentEligibilityPort> { PaymentEligibilityAdapter() }
    single<com.gatekeeper.feature.payment.domain.usecase.MpesaInitiationGuard> { MpesaPaymentProtection(get<AppSettings>().payment) }
    single<ServiceMembershipRepository> { ExposedServiceMembershipRepository(get()) }
    single { PaymentBalanceAdapter(get<PaymentDomainRepository>(), get(), get(), get(), get<ProjectStore>()) }
    single<PaymentEffects> { PaymentEffectsAdapter(get(), get<ProjectStore>(), get(), get()) }
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
    single { InitiatePayment(get(), get<PaymentProjectAdapter>(), get<PaymentBalanceAdapter>(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get(), get(), get(), get()) }
    single { ProcessPaymentEvent(get(), get(), get<PaymentProjectAdapter>(), get()) }
    single { HandlePaystackWebhook(get()) }
    single { ReplayPaystackEvent(get(), get()) }
    single { GetPaymentEventForReplay(get()) }
    single<MpesaCallbackDecoder> { JsonMpesaCallbackDecoder() }
    single { HandleMpesaCallback(get(), get(), get()) }
    single { ListPaymentEvents(get()) }
    single { VerifyPayment(mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
    single { CompletePaystackCallback(get<PaymentProjectAdapter>(), get()) }
    single { ReconcilePayments(get(), mapOf("paystack" to get(named("paystackGateway")), "mpesa" to get(named("mpesaGateway"))), get()) }
    single<ServiceRepository> { ExposedServiceRepository(get()) }
    single<SiteRepository> { ExposedSiteRepository() }
    single {
        AutoBlockerJob(
            projectRepository = get(),
            serviceRepository = get(),
            serviceInvoices = get()
        )
    }
    single {
        DeploymentUpstreamResolver(
            siteRepository = get(),
            deploymentApplicationService = com.gatekeeper.deployment.DeploymentApplicationService
        )
    }
    single {
        NginxSiteReconciliationService(
            settings = get<AppSettings>().nginx,
            siteRepository = get(),
            deploymentUpstreamResolver = get(),
            dockerGateway = get(),
            nginxConfigRenderer = get(),
            selfDomainExclusion = get()
        )
    }
    single {
        GateService(
            projects = get<ProjectQueryRepository>(),
            latestPaymentLink = get<GetLatestPaymentLink>()::invoke,
            projectFinancials = get<com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter>()::financials,
            applicationSettings = get<AppSettings>().application
        )
    }

}

fun Application.configureDependencyInjection() {
    install(Koin) {
        modules(applicationModule)
    }
}
