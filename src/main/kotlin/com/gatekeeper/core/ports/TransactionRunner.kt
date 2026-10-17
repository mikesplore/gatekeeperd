package com.gatekeeper.core.ports

interface TransactionRunner {
    fun <T> run(block: () -> T): T
}
