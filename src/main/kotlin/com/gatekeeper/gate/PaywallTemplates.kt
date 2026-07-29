package com.gatekeeper.gate

import com.gatekeeper.api.PaymentRequiredResponse
import com.gatekeeper.api.paymentRequiredResponse
import com.gatekeeper.config.AppConfig
import com.gatekeeper.paystack.ProjectPaymentService
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/**
 * Server-rendered payment wall matching the Gatekeeper admin UI purple theme.
 */
object PaywallTemplates {

    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    fun htmlPaywall(info: PaywallInfo, payEnabled: Boolean): String {
        val amountLabel = formatAmount(info.amountDue, info.currency)
        val dueLabel = info.dueDate?.format(dateFormatter)
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
            <div class="status-strip blocked">
                <span>${iconDot()}Suspended</span>
                <span class="brand">Gatekeeper</span>
            </div>
            <div class="hero">
                <div class="glyph">${iconLock()}</div>
                <p class="project-name">${escapeHtml(info.name)}</p>
                <p class="project-domain">${escapeHtml(info.domain)}</p>

                <div class="amount-block">
                    <p class="amount-label">Amount due</p>
                    <p class="amount-value"${if (info.amountDue == null) " style=\"font-size:1.75rem;\"" else ""}>$amountLabel</p>
                    ${if (dueLabel != null) """<p class="amount-due">Due <strong>$dueLabel</strong></p>""" else ""}
                </div>
            </div>
            <div class="actions">
                ${if (showPayButton) """
                <a href="$payUrl" class="btn">${iconCard()}Pay now</a>
                <p class="helper-text">You'll be redirected to Paystack to complete payment securely.</p>
                """ else """
                <span class="btn btn-disabled">Pay now unavailable</span>
                <p class="helper-text helper-text-error">${escapeHtml(payDisabledReason ?: "Payment is currently unavailable.")}</p>
                """}
            </div>
            <p class="footer-text">
                Questions? Contact
                <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}" class="link">${escapeHtml(AppConfig.supportContactEmail)}</a>
            </p>
        </div>
    </div>
            """.trimIndent()
        )
    }

    fun paymentSuccessPage(projectName: String, projectDomain: String, verified: Boolean): String {
        val statusLabel = if (verified) "Payment received" else "Processing payment"
        val noticeTitle = if (verified) {
            "$projectName will be back online shortly."
        } else {
            "We're confirming your payment."
        }
        val noticeBody = if (verified) {
            "You can close this page and retry your site in a minute."
        } else {
            "This usually takes a few seconds. Refresh this page shortly."
        }

        return pageShell(
            title = "Payment Received — ${escapeHtml(projectName)}",
            body = """
    <div class="page">
        <div class="card">
            <div class="status-strip active">
                <span>${iconDot()}$statusLabel</span>
                <span class="brand">Gatekeeper</span>
            </div>
            <div class="hero">
                <div class="glyph glyph-success">${iconCheckCircle()}</div>
                <p class="project-name">${escapeHtml(projectName)}</p>
                <p class="project-domain">${escapeHtml(projectDomain)}</p>
            </div>
            <div class="success-note">
                <p>${escapeHtml(noticeTitle)}</p>
                <p>${escapeHtml(noticeBody)}</p>
            </div>
            <p class="footer-text">
                Need help? Contact
                <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}" class="link">${escapeHtml(AppConfig.supportContactEmail)}</a>
            </p>
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

    private fun gatekeeperStyles(): String = """
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        :root {
            --foreground: #171717;
            --primary: #6C63FF;
            --primary-dark: #4F46E5;
            --muted-foreground: #737373;
            --danger: #dc2626;
            --success: #059669;
            --radius: 1rem;
        }

        body {
            font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: linear-gradient(135deg, #f5f5ff 0%, #ffffff 50%, #f5f5ff 100%);
            color: var(--foreground);
            min-height: 100vh;
            -webkit-font-smoothing: antialiased;
        }

        .page {
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1.5rem;
        }

        .card {
            width: 100%;
            max-width: 26rem;
            background: #ffffff;
            border: 1px solid #ececec;
            border-radius: var(--radius);
            overflow: hidden;
            box-shadow: 0 12px 32px rgb(108 99 255 / 0.12), 0 2px 8px rgb(0 0 0 / 0.04);
        }

        .status-strip {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0.75rem 1.5rem;
            font-size: 0.75rem;
            font-weight: 600;
            letter-spacing: 0.02em;
        }
        .status-strip.blocked { background: #fef2f2; color: var(--danger); }
        .status-strip.active { background: #ecfdf5; color: var(--success); }
        .status-strip .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; display: inline-block; margin-right: 6px; }
        .status-strip .brand { color: #a3a3a3; font-weight: 500; }

        .hero {
            padding: 2rem 1.75rem 1.5rem;
            text-align: center;
        }

        .glyph {
            width: 3.5rem;
            height: 3.5rem;
            border-radius: 50%;
            margin: 0 auto 1.25rem;
            display: flex;
            align-items: center;
            justify-content: center;
            background: linear-gradient(135deg, var(--primary), var(--primary-dark));
            color: #fff;
        }
        .glyph-success { background: linear-gradient(135deg, #10b981, #059669); }
        .glyph svg { width: 1.5rem; height: 1.5rem; }

        .project-name {
            font-size: 1.375rem;
            font-weight: 700;
            letter-spacing: -0.02em;
        }
        .project-domain {
            font-size: 0.875rem;
            color: var(--muted-foreground);
            margin-top: 0.125rem;
        }

        .amount-block {
            margin: 1.75rem 0 0.25rem;
        }
        .amount-label {
            font-size: 0.75rem;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.06em;
            color: var(--muted-foreground);
            margin-bottom: 0.25rem;
        }
        .amount-value {
            font-size: 2.75rem;
            font-weight: 800;
            letter-spacing: -0.03em;
            line-height: 1;
            color: var(--foreground);
        }
        .amount-due {
            font-size: 0.8125rem;
            color: var(--muted-foreground);
            margin-top: 0.375rem;
        }
        .amount-due strong { color: var(--foreground); font-weight: 600; }

        .actions {
            padding: 0 1.75rem 1.75rem;
        }

        .btn {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
            width: 100%;
            height: 3rem;
            border-radius: 0.75rem;
            font-size: 0.9375rem;
            font-weight: 600;
            text-decoration: none;
            border: none;
            cursor: pointer;
            background: var(--foreground);
            color: #fff;
            transition: transform 0.15s ease, background 0.15s ease;
        }
        .btn:hover { background: #000; transform: translateY(-1px); }
        .btn-disabled { background: #e5e5e5; color: #a3a3a3; pointer-events: none; }

        .helper-text {
            font-size: 0.8125rem;
            color: var(--muted-foreground);
            text-align: center;
            margin-top: 0.75rem;
            line-height: 1.5;
        }
        .helper-text-error { color: var(--danger); }

        .footer-text {
            font-size: 0.75rem;
            color: var(--muted-foreground);
            text-align: center;
            line-height: 1.5;
            padding: 0 1.75rem 1.75rem;
        }
        .link { color: var(--primary); font-weight: 600; text-decoration: none; }
        .link:hover { text-decoration: underline; }

        .success-note {
            margin: 0 1.75rem 1.75rem;
            background: #ecfdf5;
            border-radius: 0.75rem;
            padding: 1rem 1.25rem;
            text-align: center;
        }
        .success-note p:first-child { font-weight: 600; font-size: 0.9375rem; margin-bottom: 0.25rem; }
        .success-note p:last-child { font-size: 0.8125rem; color: var(--muted-foreground); }

        @media (max-width: 640px) {
            .hero { padding: 1.5rem 1.25rem 1rem; }
            .amount-value { font-size: 2.25rem; }
        }
    """.trimIndent()

    private fun iconDot(): String =
        """<span class="dot"></span>"""

    private fun iconLock(): String = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
    """.trimIndent()

    private fun iconCard(): String = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" width="18" height="18"><rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" y1="10" x2="22" y2="10"/></svg>
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