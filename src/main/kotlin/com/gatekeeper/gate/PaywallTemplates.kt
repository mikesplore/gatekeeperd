package com.gatekeeper.gate

/**
 * Simple server-rendered templates for blocked project responses.
 * No templating engine — just string substitution for v1.
 */
object PaywallTemplates {

    /**
     * HTML paywall page for frontend projects.
     */
    fun htmlPaywall(projectName: String, paymentLink: String?): String {
        val link = paymentLink ?: "#"
        return buildString {
            append("""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Payment Required — $projectName</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .card {
            background: white;
            border-radius: 16px;
            padding: 48px;
            max-width: 480px;
            width: 100%;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            text-align: center;
        }
        .icon {
            font-size: 64px;
            margin-bottom: 16px;
        }
        h1 {
            font-size: 24px;
            color: #1a1a2e;
            margin-bottom: 8px;
        }
        p {
            color: #666;
            line-height: 1.6;
            margin-bottom: 24px;
        }
        .btn {
            display: inline-block;
            background: #667eea;
            color: white;
            text-decoration: none;
            padding: 14px 36px;
            border-radius: 8px;
            font-weight: 600;
            font-size: 16px;
            transition: background 0.2s;
        }
        .btn:hover { background: #5a6fd6; }
        .contact {
            margin-top: 24px;
            font-size: 14px;
            color: #999;
        }
    </style>
</head>
<body>
    <div class="card">
        <div class="icon">🔒</div>
        <h1>Payment Required</h1>
        <p>Access to <strong>$projectName</strong> has been suspended pending payment. Please complete your payment to restore access.</p>
""")
            if (paymentLink != null) {
                append("""
        <a href="$link" class="btn" target="_blank" rel="noopener">Pay Now</a>
""")
            }
            append("""
        <p class="contact">Questions? Contact your service provider.</p>
    </div>
</body>
</html>""")
        }
    }

    /**
     * JSON response for backend API projects.
     */
    fun jsonBlocked(projectName: String, paymentLink: String?): String {
        val link = paymentLink ?: ""
        return """{"error":"payment_required","message":"Access to this API is suspended pending payment.","payment_link":"$link","contact":"support@gatekeeper.local"}"""
    }
}