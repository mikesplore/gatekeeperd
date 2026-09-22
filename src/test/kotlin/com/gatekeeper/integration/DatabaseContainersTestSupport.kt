package com.gatekeeper.integration

import com.redis.testcontainers.RedisContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer

/** Shared opt-in Testcontainers fixture for repository and migration integration tests. */
abstract class DatabaseContainersTestSupport {
    companion object {
        @JvmStatic
        protected lateinit var postgres: PostgreSQLContainer<*>
        @JvmStatic
        protected lateinit var redis: RedisContainer

        @JvmStatic
        @BeforeAll
        fun startContainers() {
            postgres = PostgreSQLContainer("postgres:16-alpine").withDatabaseName("gatekeeper_test")
                .withUsername("gatekeeper").withPassword("gatekeeper")
            redis = RedisContainer("redis:7-alpine")
            postgres.start()
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainers() {
            if (::redis.isInitialized) redis.stop()
            if (::postgres.isInitialized) postgres.stop()
        }
    }
}
