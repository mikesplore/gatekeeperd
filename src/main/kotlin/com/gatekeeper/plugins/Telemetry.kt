package com.gatekeeper.plugins

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

object Telemetry {
    private var sdk: OpenTelemetrySdk? = null
    val tracer: Tracer get() = GlobalOpenTelemetry.getTracer("gatekeeperd")

    fun init() {
        if (sdk != null) return
        val endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")?.takeIf { it.isNotBlank() }
        val exporter = if (endpoint != null) OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build() else null
        val provider = SdkTracerProvider.builder().apply {
            if (exporter != null) addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        }.build()
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).buildAndRegisterGlobal()
    }

    fun close() { sdk?.close(); sdk = null }
}
