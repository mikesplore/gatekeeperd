package com.gatekeeper.gate

import com.gatekeeper.api.PaymentRequiredResponse
import com.gatekeeper.api.paymentRequiredResponse
import com.gatekeeper.config.AppConfig
import com.gatekeeper.paystack.ProjectPaymentService
import com.gatekeeper.mpesa.MpesaClient
import java.math.BigDecimal
import java.util.Base64
import java.time.format.DateTimeFormatter

object PaywallTemplates {

    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")
    private val illustrationDataUri: String = loadIllustrationDataUri()

    fun htmlPaywall(info: PaywallInfo, payEnabled: Boolean): String {
        val amountLabel = formatAmount(info.amountDue, info.currency)
        val dueLabel = info.dueDate?.format(dateFormatter)
        val payUrl = "/api/gate/pay?project=${encode(info.slug)}"
        val mpesaUrl = "/api/mpesa/pay?project=${encode(info.slug)}"
        val illustrationBlock = if (illustrationDataUri.isNotBlank()) {
            """<img src="$illustrationDataUri" alt="Payment illustration" class="illustration">"""
        } else {
            """<div class="illustration-fallback">Payment</div>"""
        }
        val payDisabledReason = when {
            !ProjectPaymentService.isPaystackConfigured() && !MpesaClient.isConfigured() ->
                "Online payment is not configured yet. Please contact support."
            info.amountDue == null -> "No payment amount is configured for this project."
            info.clientEmail.isNullOrBlank() -> "No billing email is configured for this project."
            AppConfig.publicBaseUrl.isBlank() -> "Payment callback URL is not configured on the server."
            else -> null
        }
        val showPayButton = payEnabled && payDisabledReason == null

        return pageShell(
            title = "Payment required for ${escapeHtml(info.name)}",
            body = """
    <main class="page">
        <section class="card">
            <div class="media">
                $illustrationBlock
            </div>
            <div class="content">
                <p class="eyebrow">Payment required</p>
                <h1>${escapeHtml(info.name)}</h1>
                <p class="domain">${escapeHtml(info.domain)}</p>
                <p class="summary">
                    Please complete the payment to restore access to this project.
                </p>
                <div class="amount">
                    <span class="label">Amount due</span>
                    <strong>$amountLabel</strong>
                    ${if (dueLabel != null) """<span class="due">Due $dueLabel</span>""" else ""}
                </div>
                ${if (showPayButton) """
                <label class="label" for="payment-method">Payment method</label>
                <select id="payment-method" class="select" onchange="toggleMpesa()">
                    ${if (ProjectPaymentService.isPaystackConfigured()) "<option value=\"paystack\">Card / bank (Paystack)</option>" else ""}
                    ${if (MpesaClient.isConfigured()) "<option value=\"mpesa\">M-Pesa</option>" else ""}
                </select>
                <a id="paystack-button" href="$payUrl" class="btn">Pay with Paystack</a>
                <div id="mpesa-form" class="mpesa-form" data-url="$mpesaUrl" hidden>
                    <label class="label" for="mpesa-phone">M-Pesa phone number</label>
                    <input id="mpesa-phone" class="input" type="tel" inputmode="tel" placeholder="2547XXXXXXXX">
                    <button type="button" class="btn" onclick="payWithMpesa()">Pay with M-Pesa</button>
                    <p id="mpesa-message" class="helper"></p>
                </div>
                <p class="helper">Choose a payment method to continue.</p>
                """ else """
                <div class="btn btn-disabled">Pay now unavailable</div>
                <p class="helper helper-error">${escapeHtml(payDisabledReason ?: "Payment is currently unavailable.")}</p>
                """}
                <p class="footer">
                    Contact <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}">${escapeHtml(AppConfig.supportContactEmail)}</a>
                </p>
            </div>
        </section>
    </main>
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
<script>
function toggleMpesa() {
    var mpesa = document.getElementById('mpesa-form');
    var paystack = document.getElementById('paystack-button');
    var isMpesa = document.getElementById('payment-method').value === 'mpesa';
    mpesa.hidden = !isMpesa;
    paystack.hidden = isMpesa;
}
async function payWithMpesa() {
    var form = document.getElementById('mpesa-form');
    var phone = document.getElementById('mpesa-phone').value.trim();
    var message = document.getElementById('mpesa-message');
    if (!phone) { message.textContent = 'Enter your M-Pesa phone number.'; return; }
    message.textContent = 'Sending payment prompt…';
    try {
        var response = await fetch(form.dataset.url + '&phone=' + encodeURIComponent(phone), { method: 'POST' });
        if (!response.ok) throw new Error('Unable to initiate payment');
        message.textContent = 'Check your phone and approve the M-Pesa prompt.';
    } catch (error) { message.textContent = error.message; }
}
</script>
</body>
</html>
    """.trimIndent()

    private fun gatekeeperStyles(): String = """
        *, *::before, *::after { box-sizing: border-box; }
        body {
            margin: 0;
            min-height: 100vh;
            font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background:
                radial-gradient(circle at top left, rgba(108, 99, 255, 0.12), transparent 34%),
                linear-gradient(180deg, #f5f7ff 0%, #f8fafc 50%, #ffffff 100%);
            color: #111827;
            -webkit-font-smoothing: antialiased;
        }
        .page {
            min-height: 100vh;
            display: grid;
            place-items: center;
            padding: 1.25rem;
        }
        .card {
            width: min(100%, 30rem);
            background: #fff;
            border: 1px solid rgba(148, 163, 184, 0.18);
            border-radius: 1.5rem;
            overflow: hidden;
            box-shadow: 0 20px 60px rgba(15, 23, 42, 0.12);
        }
        .media {
            background: linear-gradient(180deg, #eef2ff 0%, #ffffff 100%);
            padding: 1.5rem 1.5rem 0;
        }
        .illustration {
            display: block;
            width: min(100%, 16rem);
            height: auto;
            margin: 0 auto;
        }
        .content {
            padding: 1.25rem 1.5rem 1.5rem;
        }
        .eyebrow {
            margin: 0;
            font-size: 0.8rem;
            font-weight: 700;
            letter-spacing: 0.08em;
            text-transform: uppercase;
            color: #6b7280;
        }
        h1 {
            margin: 0.4rem 0 0;
            font-size: 1.55rem;
            line-height: 1.2;
            letter-spacing: -0.03em;
            color: #111827;
        }
        .domain {
            margin: 0.35rem 0 0;
            color: #6b7280;
            font-size: 0.95rem;
            word-break: break-word;
        }
        .summary {
            margin: 1rem 0 1.1rem;
            color: #374151;
            line-height: 1.55;
        }
        .amount {
            display: grid;
            gap: 0.25rem;
            margin-bottom: 1.15rem;
            padding: 1rem;
            border-radius: 1rem;
            background: #f8fafc;
            border: 1px solid #e5e7eb;
        }
        .label {
            font-size: 0.75rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.06em;
            color: #6b7280;
        }
        .amount strong {
            font-size: 2.1rem;
            line-height: 1.1;
            color: #111827;
        }
        .due {
            font-size: 0.875rem;
            color: #4b5563;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            min-height: 3rem;
            border-radius: 0.9rem;
            background: linear-gradient(135deg, #111827 0%, #374151 100%);
            color: #fff;
            text-decoration: none;
            font-weight: 600;
            border: 0;
            transition: transform 0.15s ease, box-shadow 0.15s ease;
            box-shadow: 0 10px 20px rgba(17, 24, 39, 0.18);
        }
        .btn:hover {
            transform: translateY(-1px);
            box-shadow: 0 14px 24px rgba(17, 24, 39, 0.22);
        }
        .btn-disabled {
            background: #e5e7eb;
            color: #9ca3af;
            box-shadow: none;
            transform: none;
        }
        .helper,
        .footer {
            margin: 0.75rem 0 0;
            font-size: 0.875rem;
            color: #6b7280;
            line-height: 1.5;
            text-align: center;
        }
        .helper-error {
            color: #b91c1c;
        }
        .footer a {
            color: #111827;
            font-weight: 600;
            text-decoration: none;
        }
        @media (max-width: 640px) {
            .content {
                padding: 1rem 1.1rem 1.2rem;
            }
            h1 {
                font-size: 1.35rem;
            }
            .amount strong {
                font-size: 1.8rem;
            }
        }
    """.trimIndent()

    private fun formatAmount(amount: BigDecimal?, currency: String): String {
        if (amount == null) return "Not set"
        return "$currency ${amount.stripTrailingZeros().toPlainString()}"
    }

    private fun loadIllustrationDataUri(): String {
        val resourcePath = "/public/undraw_pay-online_806n.svg"
        val bytes = PaywallTemplates::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: return ""
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "data:image/svg+xml;base64,$encoded"
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
