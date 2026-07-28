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
            testOnBorrow = true
            testOnReturn = true
        }
        pool = JedisPool(config, host, port, 2000)
        logger.info("Redis connected: $host:$port")
    }

    fun get(key: String): String? {
        return pool?.resource?.use { jedis ->
            jedis.get(key)
        }
    }

    fun set(key: String, value: String, ttlSeconds: Int = 60) {
        pool?.resource?.use { jedis ->
            jedis.setex(key, ttlSeconds.toLong(), value)
        }
    }

    fun delete(key: String) {
        pool?.resource?.use { jedis ->
            jedis.del(key)
        }
    }

    fun close() {
        pool?.close()
        logger.info("Redis connection pool closed")
    }
}

fun Application.configureRedis() {
    RedisService.init(AppConfig.redisHost, AppConfig.redisPort)

    environment.monitor.subscribe(ApplicationStopping) {
        RedisService.close()
    }
}