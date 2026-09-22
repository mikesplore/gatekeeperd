ALTER TABLE sites
    ADD COLUMN IF NOT EXISTS bypass_paths TEXT NOT NULL DEFAULT '["/api/gate/","/api/paystack/","/api/mpesa/"]';
