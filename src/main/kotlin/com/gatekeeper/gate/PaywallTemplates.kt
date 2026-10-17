package com.gatekeeper.gate

import com.gatekeeper.api.PaymentRequiredResponse
import com.gatekeeper.api.paymentRequiredResponse
import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.model.PaymentMethodAvailability
import com.gatekeeper.db.tables.AccessBlockReason
import java.math.BigDecimal
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

    private data class BlockPresentation(
        val heading: String,
        val summary: String,
        val title: String,
        val theme: String,
        val iconPaths: String
    )

    // Stroke icons, drawn on a 24x24 grid, colored via currentColor.
    private const val ICON_CARD = """<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M3 10h18M7 15h4"/>"""
    private const val ICON_PAUSE = """<circle cx="12" cy="12" r="9"/><path d="M10 9v6M14 9v6"/>"""
    private const val ICON_SHIELD = """<path d="M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3z"/><path d="M12 8v4M12 15.5v.01"/>"""
    private const val ICON_POWER = """<path d="M12 3v8"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>"""
    private const val ICON_INFO = """<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8v.01"/>"""

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
                "Payment required for ${info.name}",
                "payment", ICON_CARD
            )
            AccessBlockReason.MANUAL_HOLD -> BlockPresentation(
                "Access temporarily paused",
                "An administrator has temporarily paused access to $scope.",
                "Access paused for ${info.name}",
                "hold", ICON_PAUSE
            )
            AccessBlockReason.ABUSE_TOS -> BlockPresentation(
                "Access restricted",
                "Access to $scope has been restricted. Contact support for assistance.",
                "Access restricted for ${info.name}",
                "abuse", ICON_SHIELD
            )
            AccessBlockReason.SUSPENDED_BY_REQUEST -> BlockPresentation(
                "Service suspended",
                "$scope has been suspended at the account holder\u2019s request.",
                "Service suspended for ${info.name}",
                "suspended", ICON_POWER
            )
            AccessBlockReason.OTHER -> BlockPresentation(
                "Access unavailable",
                "Access to $scope is currently unavailable. Contact support for assistance.",
                "Access unavailable for ${info.name}",
                "other", ICON_INFO
            )
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

        // Left panel: the status, stated once.
        val statusPanel = """
        <aside class="status">
            <p class="status-domain">${escapeHtml(info.domain)}</p>
            <div class="status-main">
                <svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${presentation.iconPaths}</svg>
                <h1>${escapeHtml(presentation.heading)}</h1>
                <p class="status-summary">${escapeHtml(presentation.summary)}</p>
            </div>
        </aside>
        """

        // Right panel: details.
        val scopeLabel = if (info.reasonSource == "project") "Entire project" else "This service only"
        val serviceRow = info.serviceName
            ?.let { """<div class="fact"><dt>Service</dt><dd>${escapeHtml(it)}</dd></div>""" }
            .orEmpty()
        val facts = """
        <dl class="facts">
            <div class="fact"><dt>Project</dt><dd>${escapeHtml(info.name)}</dd></div>
            $serviceRow
            <div class="fact"><dt>Applies to</dt><dd>$scopeLabel</dd></div>
        </dl>
        """
        val noteBlock = info.blockReasonNote
            ?.takeIf { it.isNotBlank() }
            ?.let {
                """<blockquote class="note"><p class="note-from">Message from the administrator</p><p class="note-body">${escapeHtml(it)}</p></blockquote>"""
            }
            .orEmpty()

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
            """<div class="invoice">
                <div class="invoice-head">
                    <span class="invoice-name">${escapeHtml(invoice.serviceName)}</span>
                    <strong class="invoice-amount">${escapeHtml(formatAmount(invoice.amountDue, invoice.currency))}</strong>
                </div>
                $due
                $payButtons
            </div>"""
        }

        val paymentControls = if (showPayButton) """
                <div class="field">
                    <label class="label" for="payment-amount">Amount to pay (${escapeHtml(info.currency)})</label>
                    <input id="payment-amount" class="input" type="number" min="0.01" max="$amountValue" step="0.01" value="$amountValue" required oninput="updatePaymentAmount()">
                    <p class="hint">You can pay part now. Access returns once the balance is paid in full.</p>
                </div>
                <div class="field">
                    <label class="label" for="payment-method">Payment method</label>
                    <select id="payment-method" class="select" onchange="toggleMpesa()">
                        ${if (paystackAvailable) "<option value=\"paystack\">Card or bank (Paystack)</option>" else ""}
                        ${if (mpesaAvailable) "<option value=\"mpesa\">M-Pesa</option>" else ""}
                    </select>
                </div>
                <a id="paystack-button" href="$payUrl" data-base-url="$payUrl" class="btn" ${if (defaultPaymentMethod == "mpesa") "hidden" else ""}>Pay with Paystack</a>
                <div id="mpesa-form" class="mpesa-form" data-base-url="$mpesaUrl" ${if (defaultPaymentMethod == "paystack") "hidden" else ""}>
                    <label class="label" for="mpesa-phone">M-Pesa phone number</label>
                    <input id="mpesa-phone" class="input" type="tel" inputmode="tel" placeholder="2547XXXXXXXX">
                    <button type="button" class="btn" onclick="payWithMpesa()">Pay with M-Pesa</button>
                    <p id="mpesa-message" class="helper"></p>
                </div>
        """ else """
                <div class="btn btn-disabled">Pay now unavailable</div>
                <p class="helper helper-error">${escapeHtml(payDisabledReason ?: "Payment is currently unavailable.")}</p>
        """

        val actionSection = when {
            serviceBillingMode -> """
            <section class="action">
                <h2>Outstanding service invoices</h2>
                <p class="hint">Each service has its own balance. Paying one service does not pay or unblock another.</p>
                $serviceBillingSection
            </section>
            """
            paymentBlock -> """
            <section class="action">
                <p class="label">Amount due</p>
                <p class="amount">${escapeHtml(amountLabel)}</p>
                ${if (dueLabel != null) """<p class="due">Due $dueLabel</p>""" else ""}
                $paymentControls
            </section>
            """
            else -> ""
        }

        val contact = escapeHtml(AppConfig.supportContactEmail)
        val contactLine = if (paymentBlock || serviceBillingMode) {
            """<p class="contact">Questions about this balance? Email <a href="mailto:$contact">$contact</a></p>"""
        } else {
            """<p class="contact">Need help? Email <a href="mailto:$contact">$contact</a></p>"""
        }

        return pageShell(
            title = escapeHtml(presentation.title),
            theme = presentation.theme,
            body = """
    <main class="gate">
        $statusPanel
        <section class="detail">
            <div class="detail-inner">
                $facts
                $noteBlock
                $actionSection
                $contactLine
            </div>
        </section>
    </main>
            """.trimIndent()
        )
    }

    fun jsonBlocked(paywall: PaywallInfo?): PaymentRequiredResponse =
        paymentRequiredResponse(paywall)

    private fun pageShell(title: String, theme: String, body: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="robots" content="noindex">
    <title>$title</title>
    <style>${gatekeeperStyles()}</style>
</head>
<body class="theme-$theme">
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
    message.textContent = 'Sending payment prompt\u2026';
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
    message.textContent = 'Sending payment prompt\u2026';
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
        [hidden] { display: none !important; }

        /* Strict black and white: black status panel, white detail panel. */
        body {
            --panel: #000000;
            --on-panel: #ffffff;
            --ink: #000000;
            --muted: #5c5c5c;
            --line: #e4e4e4;
            --surface: #ffffff;
            margin: 0;
            min-height: 100vh;
            font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            color: var(--ink);
            background: var(--surface);
            -webkit-font-smoothing: antialiased;
        }

        .gate {
            min-height: 100vh;
            display: grid;
            grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
        }

        /* Status panel */
        .status {
            background: var(--panel);
            color: var(--on-panel);
            padding: clamp(1.75rem, 4vw, 3.5rem);
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            gap: 4rem;
        }
        .status-domain {
            margin: 0;
            font-size: 0.95rem;
            color: rgba(255, 255, 255, 0.65);
            word-break: break-word;
        }
        .status-icon {
            display: block;
            width: 3.25rem;
            height: 3.25rem;
            color: var(--on-panel);
            margin-bottom: 1.5rem;
        }
        .status h1 {
            margin: 0;
            font-size: clamp(2.25rem, 4.6vw, 3.6rem);
            line-height: 1.04;
            font-weight: 700;
            letter-spacing: -0.035em;
            text-wrap: balance;
        }
        .status-summary {
            margin: 1.1rem 0 0;
            max-width: 26rem;
            font-size: 1.05rem;
            line-height: 1.55;
            color: rgba(255, 255, 255, 0.8);
        }

        /* Detail panel */
        .detail {
            display: flex;
            align-items: center;
            padding: clamp(1.75rem, 4vw, 3.5rem);
        }
        .detail-inner {
            width: 100%;
            max-width: 29rem;
            margin: 0 auto;
        }

        .facts { margin: 0; padding: 0; }
        .fact {
            display: flex;
            justify-content: space-between;
            gap: 1.5rem;
            padding: 0.8rem 0;
            border-bottom: 1px solid var(--line);
        }
        .fact:first-child { border-top: 1px solid var(--line); }
        .fact dt { color: var(--muted); font-size: 0.9rem; }
        .fact dd { margin: 0; font-weight: 600; text-align: right; word-break: break-word; }

        .note {
            margin: 1.5rem 0 0;
            padding: 0.2rem 0 0.2rem 1rem;
            border-left: 3px solid var(--panel);
        }
        .note-from { margin: 0; font-size: 0.85rem; color: var(--muted); }
        .note-body { margin: 0.3rem 0 0; font-size: 1.05rem; line-height: 1.5; }

        .action { margin-top: 2rem; }
        .action h2 { margin: 0; font-size: 1.15rem; letter-spacing: -0.01em; }
        .label { display: block; margin: 0 0 0.4rem; font-size: 0.9rem; font-weight: 600; color: var(--muted); }
        .amount {
            margin: 0;
            font-size: clamp(2.4rem, 5vw, 3.1rem);
            font-weight: 700;
            line-height: 1.05;
            letter-spacing: -0.03em;
            font-variant-numeric: tabular-nums;
        }
        .due { margin: 0.35rem 0 0; font-size: 0.9rem; color: var(--muted); }
        .action > .amount + .due, .action > .amount { margin-bottom: 0; }

        .field { margin-top: 1.25rem; }
        .field .label { margin-bottom: 0.4rem; }
        .input, .select {
            display: block;
            width: 100%;
            min-height: 3rem;
            padding: 0 0.9rem;
            font: inherit;
            color: var(--ink);
            background: #fff;
            border: 1.5px solid #bdbdbd;
            border-radius: 0.6rem;
        }
        .input:focus-visible, .select:focus-visible {
            outline: 3px solid var(--ink);
            outline-offset: 1px;
            border-color: var(--panel);
        }
        .hint { margin: 0.5rem 0 0; font-size: 0.875rem; line-height: 1.45; color: var(--muted); }

        .btn {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            min-height: 3.1rem;
            margin-top: 1.25rem;
            padding: 0 1rem;
            font: inherit;
            font-weight: 600;
            color: #fff;
            text-decoration: none;
            background: var(--panel);
            border: 0;
            border-radius: 0.6rem;
            cursor: pointer;
            transition: filter 0.15s ease;
        }
        .btn:hover { filter: brightness(1.18); }
        .btn:focus-visible { outline: 3px solid var(--ink); outline-offset: 2px; }
        .btn:disabled { opacity: 0.5; cursor: not-allowed; }
        .btn-disabled { background: #ececec; color: #8c8c8c; cursor: default; }
        .btn-disabled:hover { filter: none; }

        .mpesa-form { margin-top: 1.25rem; }
        .mpesa-form .btn { margin-top: 0.75rem; }
        .service-mpesa { margin-top: 1rem; }
        .service-mpesa .btn { margin-top: 0.6rem; }

        .helper { margin: 0.6rem 0 0; font-size: 0.875rem; line-height: 1.45; color: var(--muted); }
        .helper-error { color: #b42318; }

        .invoice { padding: 1.1rem 0; border-bottom: 1px solid var(--line); }
        .invoice:first-of-type { margin-top: 0.75rem; border-top: 1px solid var(--line); }
        .invoice-head { display: flex; justify-content: space-between; align-items: baseline; gap: 1rem; }
        .invoice-name { font-weight: 600; }
        .invoice-amount { font-size: 1.35rem; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; }
        .invoice .due { display: block; }
        .invoice .btn:first-of-type { margin-top: 0.9rem; }

        .contact { margin: 2rem 0 0; font-size: 0.9rem; color: var(--muted); }
        .contact a { color: var(--ink); font-weight: 600; text-underline-offset: 3px; }
        .contact a:focus-visible { outline: 3px solid var(--ink); outline-offset: 2px; border-radius: 2px; }

        @media (max-width: 820px) {
            .gate { grid-template-columns: 1fr; }
            .status { gap: 2.5rem; padding-bottom: 2.25rem; }
            .detail { padding-top: 1.75rem; }
        }
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