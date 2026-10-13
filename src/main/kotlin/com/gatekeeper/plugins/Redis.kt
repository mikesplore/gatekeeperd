package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig
import io.ktor.server.application.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Redis")

object RedisService {
    private var pool: JedisPool? = null

    fun init(host: String, port: Int) {
        val config = JedisPoolConfig().apply {
            maxTotal = 16
            maxIdle = 8
            minIdle = 2
            setMaxWait(java.time.Duration.ofMillis(1500))
            testOnBorrow = true
            testOnReturn = true
        }
        pool = runCatching { JedisPool(config, host, port, 2000) }
            .onFailure { logger.warn("Redis unavailable at $host:$port; continuing with degraded Redis features", it) }
            .getOrNull()
        logger.info("Redis client initialized: available={}", pool != null)
    }

    fun get(key: String): String? {
        return Telemetry.blockingSpan("redis.get") { pool?.resource?.use { jedis ->
            jedis.get(key)
        } }
    }

    /** Unlike optional cache reads, security decisions must distinguish an absent key from Redis being unavailable. */
    fun getRequired(key: String): String? {
        val activePool = pool ?: throw RedisUnavailableException()
        return Telemetry.blockingSpan("redis.get") { activePool.resource.use { jedis -> jedis.get(key) } }
    }

    fun isHealthy(): Boolean {
        return try {
            pool?.resource?.use { jedis -> jedis.ping() == "PONG" } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun set(key: String, value: String, ttlSeconds: Int = 60) {
        Telemetry.blockingSpan("redis.set") { pool?.resource?.use { jedis ->
            jedis.setex(key, ttlSeconds.toLong(), value)
        } }
    }

    /** Unlike optional cache writes, revocation writes must fail if Redis is not initialized. */
    fun setRequired(key: String, value: String, ttlSeconds: Int) {
        val activePool = pool ?: throw RedisUnavailableException()
        Telemetry.blockingSpan("redis.set") { activePool.resource.use { jedis ->
            jedis.setex(key, ttlSeconds.toLong(), value)
        } }
    }

    fun delete(key: String) {
        pool?.resource?.use { jedis ->
            jedis.del(key)
        }
    }

    fun incrementWithExpiry(key: String, ttlSeconds: Int): Long? {
        return pool?.resource?.use { jedis ->
            val count = jedis.incr(key)
            if (count == 1L) jedis.expire(key, ttlSeconds.toLong())
            count
        }
    }

    fun close() {
        pool?.close()
        logger.info("Redis connection pool closed")
    }
}

class RedisUnavailableException : RuntimeException("Redis is unavailable")

fun Application.configureRedis() {
    RedisService.init(AppConfig.redisHost, AppConfig.redisPort)

    environment.monitor.subscribe(ApplicationStopping) {
        RedisService.close()
    }
}
