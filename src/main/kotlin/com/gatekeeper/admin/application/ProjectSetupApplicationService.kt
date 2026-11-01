package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import java.math.BigDecimal
import java.text.Normalizer

object ProjectSetupApplicationService {
    fun generatedProjectSlug(projects: ProjectRepository, name: String): String {
        val base = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "project" }
            .take(54)
            .trimEnd('-')
        if (projects.findBySlug(base, includeArchived = true) == null) return base
        while (true) {
            val salt = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            val candidate = "${base.take(64 - salt.length - 1).trimEnd('-')}-$salt"
            if (projects.findBySlug(candidate, includeArchived = true) == null) return candidate
        }
    }

    fun validDeployment(body: CreateDeploymentRequest, repository: String?): Boolean =
        (repository == null || repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) &&
            (repository != null || !body.autoDeploy) &&
            (repository == null || body.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$"))) &&
            body.imageName.matches(Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")) &&
            body.registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$")) &&
            body.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$")) && body.environment.isNotBlank() &&
            (body.containerPort == null || body.containerPort in 1..65535) &&
            (body.env.keys + body.secretEnv.keys).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } &&
            body.volumes.all { it.hostPath.isNotBlank() && it.containerPath.isNotBlank() } &&
            (body.hostPort == null || body.hostPort in 1..65535)

    fun financials(project: ProjectRecord, balances: ProjectBalanceAdapter): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val values = balances.financials(project)
        return Triple(values.originalCharge + values.additionalCharges - values.discounts, values.paid, values.outstanding)
    }
}
