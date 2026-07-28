package com.gatekeeper.gate

import com.gatekeeper.api.PaymentRequiredResponse
import com.gatekeeper.api.paymentRequiredResponse
import com.gatekeeper.config.AppConfig
import com.gatekeeper.paystack.ProjectPaymentService
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/**
 * Server-rendered HTML matching the Gatekeeper admin UI (shadcn neutral theme).
 */
object PaywallTemplates {

    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    fun htmlPaywall(info: PaywallInfo, payEnabled: Boolean): String {
        val amountLabel = formatAmount(info.amountDue, info.currency)
        val dueLabel = info.dueDate?.format(dateFormatter) ?: "Not set"
        val payUrl = "/api/gate/pay?project=${encode(info.slug)}"
        val payDisabledReason = when {
            !ProjectPaymentService.isPaystackConfigured() ->
                "Online payment is not configured yet. Please contact support."
            info.amountDue == null -> "No payment amount is configured for this project."
            info.clientEmail.isNullOrBlank() -> "No billing email is configured for this project."
            AppConfig.publicBaseUrl.isBlank() -> "Payment callback URL is not configured on the server."
            else -> null
        }
        val showPayButton = payEnabled && payDisabledReason == null

        return pageShell(
            title = "Payment Required — ${escapeHtml(info.name)}",
            body = """
    <div class="page">
        <div class="card">
            <div class="card-header">
                <div class="brand-row">
                    <span class="brand">Gatekeeper</span>
                    <span class="badge badge-blocked">Suspended</span>
                </div>
                <div class="icon-wrap icon-wrap-warning">${iconLock()}</div>
                <h1 class="card-title">Payment required</h1>
                <p class="card-description">
                    Access to <strong>${escapeHtml(info.name)}</strong> is suspended until payment is received.
                </p>
            </div>
            <div class="card-content">
                <div class="details-grid">
                    ${detailRow("Project", escapeHtml(info.name))}
                    ${detailRow("Domain", escapeHtml(info.domain))}
                    ${detailRow("Due date", dueLabel)}
                </div>

                <div class="stat-card">
                    <p class="stat-label">Amount due</p>
                    <p class="stat-value">$amountLabel</p>
                </div>

                ${if (showPayButton) """
                <a href="$payUrl" class="btn btn-primary">Pay now</a>
                <p class="helper-text">You will be redirected to Paystack to complete payment securely.</p>
                """ else """
                <span class="btn btn-primary btn-disabled">Pay now unavailable</span>
                <p class="helper-text helper-text-error">${escapeHtml(payDisabledReason ?: "Payment is currently unavailable.")}</p>
                """}

                <p class="footer-text">
                    Questions? Contact
                    <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}" class="link">${escapeHtml(AppConfig.supportContactEmail)}</a>
                </p>
            </div>
        </div>
    </div>
            """.trimIndent()
        )
    }

    fun paymentSuccessPage(projectName: String, projectDomain: String, verified: Boolean): String {
        val message = if (verified) {
            "Your payment was received successfully."
        } else {
            "Your payment is being confirmed. This usually takes a few seconds."
        }

        return pageShell(
            title = "Payment Received — ${escapeHtml(projectName)}",
            body = """
    <div class="page">
        <div class="card">
            <div class="card-header card-header-center">
                <div class="brand-row brand-row-center">
                    <span class="brand">Gatekeeper</span>
                    <span class="badge badge-active">Payment received</span>
                </div>
                <div class="icon-wrap icon-wrap-success">${iconCheckCircle()}</div>
                <h1 class="card-title">Thank you for your payment</h1>
                <p class="card-description">$message</p>
            </div>
            <div class="card-content">
                <div class="details-grid">
                    ${detailRow("Project", escapeHtml(projectName))}
                    ${detailRow("Domain", escapeHtml(projectDomain))}
                    ${detailRow("Status", if (verified) "Confirmed" else "Processing")}
                </div>

                <div class="notice-card">
                    <p class="notice-title">${escapeHtml(projectName)} will be back online shortly.</p>
                    <p class="notice-body">You can close this page and retry your site in a minute.</p>
                </div>

                <p class="footer-text">
                    Need help? Contact
                    <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}" class="link">${escapeHtml(AppConfig.supportContactEmail)}</a>
                </p>
            </div>
        </div>
    </div>
            """.trimIndent()
        )
    }

    fun jsonBlocked(paywall: PaywallInfo?): PaymentRequiredResponse =
        paymentRequiredResponse(paywall)

    private fun pageShell(title: String, body: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$title</title>
    <style>${gatekeeperStyles()}</style>
</head>
<body>
$body
</body>
</html>
    """.trimIndent()

    private fun detailRow(label: String, value: String): String = """
        <div class="detail-row">
            <span class="detail-label">$label</span>
            <span class="detail-value">$value</span>
        </div>
    """.trimIndent()

    private fun gatekeeperStyles(): String = """
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        :root {
            --background: #ffffff;
            --foreground: #0a0a0a;
            --card: #ffffff;
            --card-foreground: #0a0a0a;
            --primary: #171717;
            --primary-foreground: #fafafa;
            --muted: #f5f5f5;
            --muted-foreground: #737373;
            --border: #e5e5e5;
            --radius: 0.625rem;
            --radius-xl: 0.75rem;
            --shadow: 0 1px 2px 0 rgb(0 0 0 / 0.05);
        }

        body {
            font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: color-mix(in oklab, var(--muted) 40%, var(--background));
            color: var(--foreground);
            -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale;
            min-height: 100vh;
        }

        .page {
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1rem;
        }

        .card {
            width: 100%;
            max-width: 28rem;
            background: var(--card);
            color: var(--card-foreground);
            border: 1px solid var(--border);
            border-radius: var(--radius-xl);
            box-shadow: var(--shadow);
        }

        .card-header {
            display: flex;
            flex-direction: column;
            gap: 0.375rem;
            padding: 1.5rem 1.5rem 0;
        }

        .card-header-center { text-align: center; align-items: center; }

        .brand-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 0.75rem;
            margin-bottom: 0.5rem;
        }

        .brand-row-center { justify-content: center; flex-wrap: wrap; }

        .brand {
            font-size: 0.875rem;
            font-weight: 600;
            letter-spacing: -0.01em;
            color: var(--foreground);
        }

        .badge {
            display: inline-flex;
            align-items: center;
            border-radius: calc(var(--radius) - 2px);
            border: 1px solid transparent;
            padding: 0.125rem 0.625rem;
            font-size: 0.75rem;
            font-weight: 600;
            line-height: 1.25rem;
        }

        .badge-blocked {
            background: rgb(239 68 68 / 0.15);
            color: #dc2626;
            border-color: rgb(239 68 68 / 0.2);
        }

        .badge-active {
            background: rgb(16 185 129 / 0.15);
            color: #059669;
            border-color: rgb(16 185 129 / 0.2);
        }

        .icon-wrap {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 2.75rem;
            height: 2.75rem;
            border-radius: var(--radius);
            margin: 0.5rem 0 0.25rem;
        }

        .card-header-center .icon-wrap { margin-left: auto; margin-right: auto; }

        .icon-wrap-warning {
            background: rgb(239 68 68 / 0.1);
            color: #dc2626;
        }

        .icon-wrap-success {
            background: rgb(16 185 129 / 0.1);
            color: #059669;
        }

        .icon-wrap svg { width: 1.25rem; height: 1.25rem; }

        .card-title {
            font-size: 1.25rem;
            font-weight: 600;
            line-height: 1;
            letter-spacing: -0.025em;
        }

        .card-description {
            font-size: 0.875rem;
            line-height: 1.5;
            color: var(--muted-foreground);
            margin-top: 0.375rem;
        }

        .card-description strong { color: var(--foreground); font-weight: 600; }

        .card-content {
            padding: 1.5rem;
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }

        .details-grid {
            display: flex;
            flex-direction: column;
            gap: 0.875rem;
            padding: 1rem;
            border: 1px solid var(--border);
            border-radius: var(--radius);
            background: color-mix(in oklab, var(--muted) 35%, var(--background));
        }

        .detail-row { display: flex; flex-direction: column; gap: 0.25rem; }

        .detail-label {
            font-size: 0.75rem;
            font-weight: 500;
            letter-spacing: 0.04em;
            text-transform: uppercase;
            color: var(--muted-foreground);
        }

        .detail-value {
            font-size: 0.875rem;
            color: var(--foreground);
            word-break: break-word;
        }

        .stat-card {
            border: 1px solid var(--border);
            border-radius: var(--radius-xl);
            padding: 1rem 1.25rem;
            box-shadow: var(--shadow);
        }

        .stat-label {
            font-size: 0.875rem;
            font-weight: 500;
            color: var(--muted-foreground);
            margin-bottom: 0.25rem;
        }

        .stat-value {
            font-size: 1.875rem;
            font-weight: 700;
            letter-spacing: -0.025em;
            color: #dc2626;
            line-height: 1.1;
        }

        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            height: 2.5rem;
            padding: 0 1rem;
            border-radius: calc(var(--radius) - 2px);
            font-size: 0.875rem;
            font-weight: 500;
            line-height: 1;
            text-decoration: none;
            transition: background-color 0.15s ease, opacity 0.15s ease;
            border: none;
            cursor: pointer;
        }

        .btn-primary {
            background: var(--primary);
            color: var(--primary-foreground);
        }

        .btn-primary:hover { background: color-mix(in oklab, var(--primary) 90%, transparent); }

        .btn-disabled {
            opacity: 0.5;
            pointer-events: none;
            cursor: not-allowed;
        }

        .helper-text {
            font-size: 0.875rem;
            line-height: 1.5;
            color: var(--muted-foreground);
            text-align: center;
        }

        .helper-text-error { color: #dc2626; }

        .notice-card {
            border: 1px solid rgb(16 185 129 / 0.25);
            background: rgb(16 185 129 / 0.08);
            border-radius: var(--radius);
            padding: 1rem 1.25rem;
            text-align: center;
        }

        .notice-title {
            font-size: 0.9375rem;
            font-weight: 600;
            color: var(--foreground);
            margin-bottom: 0.375rem;
        }

        .notice-body {
            font-size: 0.875rem;
            color: var(--muted-foreground);
            line-height: 1.5;
        }

        .footer-text {
            font-size: 0.8125rem;
            color: var(--muted-foreground);
            text-align: center;
            line-height: 1.5;
        }

        .link {
            color: var(--foreground);
            font-weight: 500;
            text-decoration: underline;
            text-underline-offset: 4px;
        }

        .link:hover { opacity: 0.8; }
    """.trimIndent()

    private fun iconLock(): String = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
    """.trimIndent()

    private fun iconCheckCircle(): String = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/></svg>
    """.trimIndent()

    private fun formatAmount(amount: BigDecimal?, currency: String): String {
        if (amount == null) return "Not set"
        return "$currency ${amount.stripTrailingZeros().toPlainString()}"
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
