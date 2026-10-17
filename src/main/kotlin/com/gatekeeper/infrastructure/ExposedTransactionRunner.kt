package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.TransactionRunner
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = transaction { block() }
}
