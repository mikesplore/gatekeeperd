package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectQueryRepository

class GateApplicationService(
    private val projects: ProjectQueryRepository,
    private val gate: GateService
) {
    fun check(slug: String, domain: String? = null): GateResult = gate.check(slug, domain)
    fun findProject(slug: String) = projects.findGateTargetBySlug(slug)?.project
}
