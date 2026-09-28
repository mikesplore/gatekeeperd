package com.gatekeeper.gate

import com.gatekeeper.api.PaymentRequiredResponse
import com.gatekeeper.api.paymentRequiredResponse
import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.model.PaymentMethodAvailability
import com.gatekeeper.db.tables.AccessBlockReason
import java.math.BigDecimal
import java.util.Base64
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.util.UUID

object PaywallTemplates {

    data class ServiceInvoiceDue(
        val serviceId: UUID,
        val serviceName: String,
        val amountDue: BigDecimal,
        val currency: String,
        val dueDate: LocalDate?
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")
    private val illustrationDataUri: String = loadIllustrationDataUri()

    fun htmlBlockWall(
        info: PaywallInfo,
        payEnabled: Boolean,
        paymentMethods: PaymentMethodAvailability,
        serviceInvoices: List<ServiceInvoiceDue> = emptyList(),
        serviceBillingMode: Boolean = false
    ): String {
        val amountLabel = formatAmount(info.amountDue, info.currency)
        val amountValue = info.amountDue?.stripTrailingZeros()?.toPlainString().orEmpty()
        val dueLabel = info.dueDate?.format(dateFormatter)
        val payUrl = "/api/gate/pay?project=${encode(info.slug)}"
        val mpesaUrl = "/api/mpesa/pay?project=${encode(info.slug)}"
        val mpesaAvailable = paymentMethods.mpesa
        val paystackAvailable = paymentMethods.paystack
        val defaultPaymentMethod = if (paystackAvailable) "paystack" else "mpesa"
        val scope = if (info.reasonSource == "project") "this project" else "this service"
        val presentation = when (info.blockReasonCode) {
            AccessBlockReason.PAYMENT -> BlockPresentation(
                "Payment required",
                "Access is paused because $scope has an outstanding payment.",
                "Payment required for ${info.name}"
            )
            AccessBlockReason.MANUAL_HOLD -> BlockPresentation(
                "Access temporarily paused",
                "An administrator has temporarily paused access to $scope.",
                "Access paused for ${info.name}"
            )
            AccessBlockReason.ABUSE_TOS -> BlockPresentation(
                "Access restricted",
                "Access to $scope has been restricted. Contact support for assistance.",
                "Access restricted for ${info.name}"
            )
            AccessBlockReason.SUSPENDED_BY_REQUEST -> BlockPresentation(
                "Service suspended",
                "$scope has been suspended at the account holder’s request.",
                "Service suspended for ${info.name}"
            )
            AccessBlockReason.OTHER -> BlockPresentation(
                "Access unavailable",
                "Access to $scope is currently unavailable. Contact support for assistance.",
                "Access unavailable for ${info.name}"
            )
        }
        val illustrationBlock = if (info.blockReasonCode == AccessBlockReason.PAYMENT && illustrationDataUri.isNotBlank()) {
            """<img src="$illustrationDataUri" alt="Payment illustration" class="illustration">"""
        } else {
            """<div class="illustration-fallback">${escapeHtml(presentation.heading)}</div>"""
        }
        val paymentBlock = info.blockReasonCode == AccessBlockReason.PAYMENT
        val payDisabledReason = when {
            !paystackAvailable && !mpesaAvailable ->
                "Online payment is not configured yet. Please contact support."
            info.amountDue == null -> "No payment amount is configured for this project."
            info.amountDue <= BigDecimal.ZERO -> "This project has no outstanding balance."
            info.customerEmail.isNullOrBlank() -> "No billing email is configured for this project."
            AppConfig.publicBaseUrl.isBlank() -> "Payment callback URL is not configured on the server."
            else -> null
        }
        val showPayButton = paymentBlock && payEnabled && payDisabledReason == null
        val serviceBillingSection = if (serviceInvoices.isEmpty()) {
            "<p class=\"helper\">No outstanding service invoices were found. Contact support if you believe this is incorrect.</p>"
        } else serviceInvoices.joinToString(separator = "") { invoice ->
            val serviceParam = "&serviceId=${encode(invoice.serviceId.toString())}"
            val servicePayUrl = "/api/gate/pay?project=${encode(info.slug)}$serviceParam"
            val serviceMpesaUrl = "/api/mpesa/pay?project=${encode(info.slug)}$serviceParam"
            val due = invoice.dueDate?.format(dateFormatter)?.let { "<span class=\"due\">Due ${escapeHtml(it)}</span>" }.orEmpty()
            val payButtons = buildString {
                val canPaystack = paystackAvailable && !info.customerEmail.isNullOrBlank() && AppConfig.publicBaseUrl.isNotBlank()
                if (canPaystack && payEnabled) append("<a class=\"btn\" href=\"$servicePayUrl\">Pay ${escapeHtml(invoice.serviceName)} with Paystack</a>")
                else if (paystackAvailable && payEnabled && info.customerEmail.isNullOrBlank()) append("<p class=\"helper helper-error\">Paystack is unavailable because no billing email is configured.</p>")
                if (mpesaAvailable && payEnabled) {
                    val serviceKey = invoice.serviceId.toString()
                    append("""<div class="service-mpesa">
                        <label class="label" for="phone-$serviceKey">M-Pesa phone number</label>
                        <input id="phone-$serviceKey" class="input" type="tel" inputmode="tel" placeholder="2547XXXXXXXX">
                        <button type="button" class="btn" onclick="payServiceWithMpesa('$serviceMpesaUrl','${invoice.amountDue.stripTrailingZeros().toPlainString()}','phone-$serviceKey','message-$serviceKey')">Pay ${escapeHtml(invoice.serviceName)} with M-Pesa</button>
                        <p id="message-$serviceKey" class="helper"></p>
                    </div>""")
                }
                if (!payEnabled || (!canPaystack && !mpesaAvailable)) append("<p class=\"helper helper-error\">Online payment is currently unavailable. Contact support.</p>")
            }
            """<div class="amount service-invoice">
                <span class="label">${escapeHtml(invoice.serviceName)}</span>
                <strong>${escapeHtml(formatAmount(invoice.amountDue, invoice.currency))}</strong>
                $due
                $payButtons
            </div>"""
        }

        return pageShell(
            title = escapeHtml(presentation.title),
            body = """
    <main class="page">
        <section class="card">
            <div class="media">
                $illustrationBlock
            </div>
            <div class="content">
                <p class="eyebrow">${escapeHtml(presentation.heading)}</p>
                <h1>${escapeHtml(info.name)}</h1>
                <p class="domain">${escapeHtml(info.domain)}</p>
                ${info.serviceName?.let { "<p class=\"domain\">Service: ${escapeHtml(it)}</p>" }.orEmpty()}
                <p class="domain">Reason set at: ${if (info.reasonSource == "project") "project" else "service"} level</p>
                <p class="summary">${escapeHtml(presentation.summary)}</p>
                ${info.blockReasonNote?.takeIf { it.isNotBlank() }?.let { "<p class=\"helper\">${escapeHtml(it)}</p>" }.orEmpty()}
                ${if (serviceBillingMode) """
                <h2 class="section-title">Outstanding service invoices</h2>
                <p class="helper">Each service has a separate balance. Paying one service will not pay or unblock another.</p>
                $serviceBillingSection
                """ else if (paymentBlock) """
                <div class="amount">
                    <span class="label">Amount due</span>
                    <strong>$amountLabel</strong>
                    ${if (dueLabel != null) """<span class="due">Due $dueLabel</span>""" else ""}
                </div>
                ${if (showPayButton) """
                <label class="label" for="payment-amount">Amount to pay (${escapeHtml(info.currency)})</label>
                <input id="payment-amount" class="input" type="number" min="0.01" max="$amountValue" step="0.01" value="$amountValue" required oninput="updatePaymentAmount()">
                <p class="helper">You can pay part now; access is restored when the balance is paid in full.</p>
                <label class="label" for="payment-method">Payment method</label>
                <select id="payment-method" class="select" onchange="toggleMpesa()">
                    ${if (paystackAvailable) "<option value=\"paystack\">Card / bank (Paystack)</option>" else ""}
                    ${if (mpesaAvailable) "<option value=\"mpesa\">M-Pesa</option>" else ""}
                </select>
                <a id="paystack-button" href="$payUrl" data-base-url="$payUrl" class="btn" ${if (defaultPaymentMethod == "mpesa") "hidden" else ""}>Pay with Paystack</a>
                <div id="mpesa-form" class="mpesa-form" data-base-url="$mpesaUrl" ${if (defaultPaymentMethod == "paystack") "hidden" else ""}>
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
                """ else """
                <p class="footer">
                    Contact <a href="mailto:${escapeHtml(AppConfig.supportContactEmail)}">${escapeHtml(AppConfig.supportContactEmail)}</a> for assistance.
                </p>
                """}
            </div>
        </section>
    </main>
            """.trimIndent()
        )
    }

    fun jsonBlocked(paywall: PaywallInfo?): PaymentRequiredResponse =
        paymentRequiredResponse(paywall)

    private data class BlockPresentation(val heading: String, val summary: String, val title: String)

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
    updatePaymentAmount();
}
function updatePaymentAmount() {
    var amount = document.getElementById('payment-amount').value.trim();
    var paystack = document.getElementById('paystack-button');
    var mpesa = document.getElementById('mpesa-form');
    paystack.href = paystack.dataset.baseUrl + '&amount=' + encodeURIComponent(amount);
    mpesa.dataset.url = mpesa.dataset.baseUrl + '&amount=' + encodeURIComponent(amount);
    if (!Number.isInteger(Number(amount))) mpesa.querySelector('button').disabled = true;
    else mpesa.querySelector('button').disabled = false;
}
async function payWithMpesa() {
    var form = document.getElementById('mpesa-form');
    var phone = document.getElementById('mpesa-phone').value.trim();
    var message = document.getElementById('mpesa-message');
    var amount = document.getElementById('payment-amount');
    if (!amount.reportValidity()) return;
    if (!Number.isInteger(Number(amount.value))) { message.textContent = 'M-Pesa payments must be a whole KES amount.'; return; }
    if (!phone) { message.textContent = 'Enter your M-Pesa phone number.'; return; }
    message.textContent = 'Sending payment prompt…';
    try {
        var response = await fetch(form.dataset.url + '&phone=' + encodeURIComponent(phone), { method: 'POST' });
        if (!response.ok) throw new Error('Unable to initiate payment');
        message.textContent = 'Check your phone and approve the M-Pesa prompt.';
    } catch (error) { message.textContent = error.message; }
}
async function payServiceWithMpesa(baseUrl, amount, phoneId, messageId) {
    var phone = document.getElementById(phoneId).value.trim();
    var message = document.getElementById(messageId);
    if (!Number.isInteger(Number(amount))) { message.textContent = 'M-Pesa payments must be a whole KES amount.'; return; }
    if (!phone) { message.textContent = 'Enter your M-Pesa phone number.'; return; }
    message.textContent = 'Sending payment prompt…';
    try {
        var response = await fetch(baseUrl + '&amount=' + encodeURIComponent(amount) + '&phone=' + encodeURIComponent(phone), { method: 'POST' });
        if (!response.ok) throw new Error('Unable to initiate payment');
        message.textContent = 'Check your phone and approve the M-Pesa prompt.';
    } catch (error) { message.textContent = error.message; }
}
if (document.getElementById('payment-method')) {
    toggleMpesa();
    document.getElementById('paystack-button').addEventListener('click', function(event) {
        if (!document.getElementById('payment-amount').reportValidity()) event.preventDefault();
    });
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
        .section-title { margin: 1rem 0 0.25rem; font-size: 1.1rem; }
        .service-invoice { margin-top: 0.85rem; }
        .service-invoice strong { font-size: 1.55rem; }
        .service-invoice .btn { margin-top: 0.65rem; }
        .service-mpesa { display: grid; gap: 0.5rem; margin-top: 0.85rem; }
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
