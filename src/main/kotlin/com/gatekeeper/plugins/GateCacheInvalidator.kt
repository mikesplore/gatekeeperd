package com.gatekeeper.plugins

import org.slf4j.LoggerFactory

/** Removes legacy gate-status entries after a billing or access-state mutation. */
class GateCacheInvalidator(
    private val deleteKey: (String) -> Unit = RedisService::deleteRequired
) {
    fun invalidate(siteSlugs: Iterable<String>) {
        siteSlugs.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::keyForSiteSlug)
            .distinct()
            .forEach { key ->
                try {
                    deleteKey(key)
                } catch (e: Exception) {
                    Metrics.increment("gate.cache_invalidation_failure")
                    logger.warn("Failed to invalidate gate authorization cache key={}", key, e)
                }
            }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GateCacheInvalidator::class.java)
        private const val PREFIX = "project:status:"

        /** Site slug is the canonical identity; requested project aliases are not cache identities. */
        fun keyForSiteSlug(siteSlug: String): String = PREFIX + siteSlug.trim().lowercase()
    }
}
