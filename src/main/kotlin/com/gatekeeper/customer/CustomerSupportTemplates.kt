package com.gatekeeper.customer

data class CustomerSupportServiceView(
    val projectName: String,
    val serviceName: String,
    val domain: String,
    val accessStatus: String,
    val statusReason: String,
    val availabilityStatus: String,
    val availabilityMessage: String,
    val invoiceDescription: String?,
    val amountDue: String?,
    val dueDate: String?,
    val paymentUrl: String?
)

object CustomerSupportTemplates {
    fun landing(supportEmail: String, error: String? = null): String = page(
        "Gatekeeperd Support",
        """
        <header class="top"><a class="brand" href="/">${shield()}<span><strong>Gatekeeperd Support</strong><small>Customer assistance</small></span></a></header>
        <main class="wrap">
          <section class="hero"><p class="eyebrow">HOSTING SUPPORT</p><h1>Your hosting,<br>at a glance.</h1><p>Check your hosted services, payment status and invoices in one place.</p>
            ${error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()}
            <form class="signin" method="post" action="/support/access/request">
              <label for="email">Email address used for your hosting account</label>
              <div class="form-row"><input id="email" name="email" type="email" autocomplete="email" required maxlength="254" placeholder="you@example.com"><button type="submit">Check my services <span aria-hidden="true">→</span></button></div>
              <p class="hint">We’ll email you a one-time code. No password or account setup needed.</p>
            </form>
          </section>
          <section class="benefits" aria-label="What you can check">
            <article><span class="icon">${receiptIcon()}</span><h2>Invoices and payments</h2><p>See outstanding balances, due dates and payment history.</p></article>
            <article><span class="icon">${shield()}</span><h2>Service access</h2><p>See whether hosting access is active or paused, and why.</p></article>
            <article><span class="icon">${helpIcon()}</span><h2>Get support</h2><p>Find answers and contact your hosting provider if you need help.</p></article>
          </section>
          <section class="faq"><div><p class="eyebrow">HELP CENTER</p><h2>Common questions</h2></div><div class="questions">
            <details><summary>What happens if my website is suspended?</summary><p>We’ll show the reason and any outstanding balance. After the required payment is confirmed, hosting access can be restored.</p></details>
            <details><summary>Does “access active” mean my website is online?</summary><p>It means Gatekeeperd is allowing traffic through. It does not by itself confirm that the website application is responding. Contact your hosting provider if the site still does not load.</p></details>
            <details><summary>How do I pay?</summary><p>Use the payment link shown with an outstanding invoice. Never share your password, card security code or mobile money PIN by email.</p></details>
          </div></section>
          <footer>Need help? <a href="mailto:${escapeAttribute(supportEmail)}">${escape(supportEmail)}</a></footer>
        </main>
        """.trimIndent()
    )

    fun codeEntry(challengeId: String, error: String? = null): String = page(
        "Verify your email · Gatekeeperd Support",
        """
        <header class="top"><a class="brand" href="/">${shield()}<span><strong>Gatekeeperd Support</strong><small>Secure sign-in</small></span></a></header>
        <main class="verify-wrap"><section class="verify-card">
          <span class="icon large">${mailIcon()}</span><p class="eyebrow">EMAIL VERIFICATION</p><h1>Check your inbox</h1>
          <p class="copy">If a hosting account matches that email, a one-time code has been sent. The code expires in 5 minutes.</p>
          ${error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()}
          <form method="post" action="/support/access/verify">
            <input type="hidden" name="challenge" value="${escapeAttribute(challengeId)}">
            <label for="code">6-digit code</label><input class="code" id="code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
            <button type="submit">Continue</button>
          </form>
          <a class="back" href="/">Use a different email</a>
        </section></main>
        """.trimIndent()
    )

    fun services(customerName: String, services: List<CustomerSupportServiceView>, supportEmail: String): String = page(
        "Your hosted services · Gatekeeperd Support",
        """
        <header class="top"><a class="brand" href="/">${shield()}<span><strong>Gatekeeperd Support</strong><small>Customer assistance</small></span></a>
          <form method="post" action="/support/logout"><button class="quiet" type="submit">Sign out</button></form></header>
        <main class="wrap dashboard">
          <p class="eyebrow">YOUR HOSTING</p><h1>Hello, ${escape(customerName)}.</h1><p class="lead">Here are the services and billing details associated with your account.</p>
          <div class="notice"><strong>Access status is not a health check.</strong> “Active” means hosting access is allowed. If a website still will not load, contact your hosting provider.</div>
          ${if (services.isEmpty()) "<section class=\"empty\"><h2>No hosted services found</h2><p>Contact your hosting provider to check which email is linked to your account.</p></section>" else services.joinToString("\n") { serviceCard(it) }}
          <footer>Need help? <a href="mailto:${escapeAttribute(supportEmail)}">Contact your hosting provider</a></footer>
        </main>
        """.trimIndent()
    )

    private fun serviceCard(service: CustomerSupportServiceView): String {
        val active = service.accessStatus.equals("active", ignoreCase = true)
        val amount = service.amountDue?.let { """<div class="billing"><div><span>Outstanding balance</span><strong>${escape(it)}</strong></div>${service.dueDate?.let { date -> "<div><span>Due date</span><strong>${escape(date)}</strong></div>" }.orEmpty()}</div>""" }.orEmpty()
        val invoice = service.invoiceDescription?.let { "<p class=\"invoice\">Latest invoice · ${escape(it)}</p>" }.orEmpty()
        val payment = if (service.amountDue != null && service.paymentUrl != null) "<a class=\"pay\" href=\"${escapeAttribute(service.paymentUrl)}\">Pay outstanding invoice <span aria-hidden=\"true\">→</span></a>" else ""
        return """
          <article class="service-card">
            <div class="service-head"><div><p class="eyebrow">HOSTED SERVICE</p><h2>${escape(service.serviceName)}</h2><p class="domain">${escape(service.domain)}</p></div>
              <span class="status ${if (active) "active" else "paused"}">${if (active) "Access active" else "Suspended"}</span></div>
            <p class="reason">${escape(service.statusReason)}</p>
            <div class="availability ${escapeAttribute(service.availabilityStatus)}"><span class="availability-dot"></span><span><strong>${escape(service.availabilityMessage)}</strong><small>Hosting connection check</small></span></div>
            <p class="project">Project · ${escape(service.projectName)}</p>
            $invoice
            $amount
            $payment
          </article>
        """.trimIndent()
    }

    private fun page(title: String, content: String): String = """
      <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex"><title>${escape(title)}</title>
      <style>
      *{box-sizing:border-box}body{margin:0;background:#f7f8fa;color:#101820;font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}a{color:inherit}.top{height:72px;padding:0 max(24px,calc((100vw - 1120px)/2));display:flex;align-items:center;justify-content:space-between;background:#fff;border-bottom:1px solid #e5e9ed}.brand{display:flex;align-items:center;gap:12px;text-decoration:none}.brand>svg{width:34px;height:34px;padding:6px;background:#101820;color:#fff;border-radius:10px}.brand strong,.brand small{display:block}.brand strong{font-size:14px}.brand small{margin-top:2px;color:#66727d;font-size:12px}.wrap{max-width:1120px;margin:0 auto;padding:64px 24px 32px}.hero{max-width:740px}.eyebrow{margin:0 0 12px;color:#087f5b;font-size:12px;font-weight:700;letter-spacing:.12em}.hero h1,.dashboard h1{margin:0;font-size:clamp(42px,6vw,68px);line-height:1.02;letter-spacing:-.055em}.hero>p:not(.eyebrow),.lead{max-width:640px;margin:18px 0 0;color:#596672;font-size:18px;line-height:1.65}.signin{margin-top:32px;padding:24px;background:#fff;border:1px solid #e2e7eb;border-radius:16px;box-shadow:0 8px 24px #15202b0a}.signin label,form label{display:block;margin-bottom:9px;font-size:14px;font-weight:600}.form-row{display:flex;gap:10px}.form-row input,.verify-card input{min-width:0;flex:1;height:48px;padding:0 14px;border:1px solid #cbd3d9;border-radius:8px;font:inherit}.form-row input:focus,.verify-card input:focus{outline:3px solid #087f5b30;border-color:#087f5b}button,.pay{min-height:48px;padding:0 18px;border:0;border-radius:8px;background:#101820;color:white;font:inherit;font-weight:600;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;justify-content:center;gap:10px}.hint{margin:10px 0 0;color:#66727d;font-size:13px}.benefits{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-top:40px}.benefits article,.service-card,.empty{padding:22px;background:#fff;border:1px solid #e2e7eb;border-radius:14px}.icon{color:#087f5b}.icon svg{width:23px;height:23px}.benefits h2{margin:14px 0 7px;font-size:16px}.benefits p,.questions p{margin:0;color:#66727d;font-size:14px;line-height:1.6}.faq{display:grid;grid-template-columns:240px 1fr;gap:40px;margin-top:68px}.faq h2{margin:0;font-size:25px;letter-spacing:-.03em}.questions{background:#fff;border:1px solid #e2e7eb;border-radius:14px;padding:0 20px}.questions details{padding:18px 0;border-bottom:1px solid #e8ecef}.questions details:last-child{border:0}.questions summary{cursor:pointer;font-size:15px;font-weight:600}.questions p{padding-top:10px}.wrap footer{margin-top:48px;padding-top:20px;border-top:1px solid #e0e5e9;color:#66727d;font-size:14px}.wrap footer a{font-weight:600;color:#101820}.verify-wrap{min-height:calc(100vh - 72px);display:grid;place-items:center;padding:32px 20px}.verify-card{width:min(100%,450px);padding:36px;background:white;border:1px solid #e2e7eb;border-radius:16px;box-shadow:0 8px 24px #15202b0a}.icon.large{display:grid;place-items:center;width:48px;height:48px;border-radius:12px;background:#e8f5f0}.icon.large svg{width:24px;height:24px}.verify-card .eyebrow{margin-top:24px}.verify-card h1{margin:0;font-size:32px;letter-spacing:-.04em}.copy{color:#66727d;line-height:1.6}.verify-card form{margin-top:24px}.verify-card input{width:100%;font-size:20px;letter-spacing:.3em}.verify-card button{width:100%;margin-top:14px}.back{display:inline-block;margin-top:20px;color:#596672;font-size:14px}.error{padding:12px 14px;background:#fff1f0;border-radius:8px;color:#a12622;font-size:14px}.dashboard{padding-top:52px}.dashboard h1{font-size:clamp(36px,5vw,52px)}.lead{margin-top:10px;font-size:16px}.notice{margin:28px 0;padding:16px 18px;background:#edf4f1;border-left:3px solid #087f5b;border-radius:4px 10px 10px 4px;color:#36554a;font-size:14px;line-height:1.55}.service-card{margin-top:16px;padding:24px}.service-head{display:flex;justify-content:space-between;gap:20px;align-items:flex-start}.service-head h2{margin:0;font-size:23px;letter-spacing:-.03em}.service-head .eyebrow{margin-bottom:8px}.domain,.project,.invoice{margin:6px 0 0;color:#66727d;font-size:14px;overflow-wrap:anywhere}.status{flex-shrink:0;padding:7px 10px;border-radius:999px;font-size:12px;font-weight:700}.status.active{background:#e4f5ed;color:#087f5b}.status.paused{background:#fff0ed;color:#b23b2a}.reason{margin:18px 0 0;font-size:14px;line-height:1.55}.availability{display:flex;align-items:center;gap:10px;margin-top:14px;padding:12px 14px;border-radius:9px;background:#f3f5f6}.availability-dot{width:9px;height:9px;border-radius:50%;background:#8a969f}.availability.responding{background:#eaf5ef;color:#286349}.availability.responding .availability-dot{background:#20905e}.availability.issue{background:#fff0ed;color:#9c3529}.availability.issue .availability-dot{background:#cf4438}.availability strong,.availability small{display:block}.availability strong{font-size:13px}.availability small{margin-top:3px;color:#66727d;font-size:11px}.project{margin-top:14px}.billing{display:flex;gap:32px;margin-top:20px;padding:16px 0;border-top:1px solid #e5e9ed}.billing span,.billing strong{display:block}.billing span{color:#66727d;font-size:12px}.billing strong{margin-top:4px;font-size:16px}.pay{min-height:44px;background:#087f5b}.quiet{min-height:38px;padding:0 14px;background:#fff;color:#33404a;border:1px solid #dbe1e6;font-size:13px}.empty{margin-top:18px}.empty h2{margin:0;font-size:18px}.empty p{color:#66727d;font-size:14px}@media(max-width:760px){.wrap{padding-top:42px}.benefits{grid-template-columns:1fr}.faq{grid-template-columns:1fr;gap:18px;margin-top:48px}.form-row{flex-direction:column}.form-row button{width:100%}.top{padding:0 20px}.service-head{flex-direction:column}.billing{gap:22px}.verify-card{padding:26px}}
      </style></head><body>$content</body></html>
    """.trimIndent()

    private fun shield() = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 20 6v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3Z"/><path d="m9 12 2 2 4-4"/></svg>"""
    private fun receiptIcon() = """<span aria-hidden="true">▤</span>"""
    private fun helpIcon() = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.6 9a2.5 2.5 0 1 1 4.2 1.8c-1.2 1-1.8 1.2-1.8 2.7M12 17h.01"/></svg>"""
    private fun mailIcon() = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></svg>"""

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun escapeAttribute(value: String): String = escape(value)
}
