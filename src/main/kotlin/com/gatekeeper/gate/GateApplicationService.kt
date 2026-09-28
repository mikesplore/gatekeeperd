package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectQueryRepository

class GateApplicationService(
    private val projects: ProjectQueryRepository,
    private val gate: GateService
) {
    fun check(slug: String): GateResult = gate.check(slug)
    fun findProject(slug: String) = projects.findBySlug(slug)
}
