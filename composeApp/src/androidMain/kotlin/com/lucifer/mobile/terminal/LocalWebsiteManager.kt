package com.lucifer.mobile.terminal

import android.content.Context
import android.util.Base64

/** Local-only starter sites for Termux. Processes bind to loopback; this is not public hosting. */
object LocalWebsiteManager {
    private const val STATIC_SESSION = "lucifer-site-static"
    private const val API_SESSION = "lucifer-site-api"
    private const val PAYMENT_SESSION = "lucifer-payment-demo"
    private const val STATIC_PORT = 8765
    private const val API_PORT = 8766
    private const val PAYMENT_PORT = 8767

    private val staticHtml = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lucifer Site · Local Preview</title><style>
:root{color-scheme:dark;font:16px system-ui;background:#0c0a10;color:#f4edf5}body{max-width:760px;margin:12vh auto;padding:24px}
small{color:#d9b942;letter-spacing:.16em}h1{font-size:clamp(2.4rem,8vw,5rem);margin:.4em 0}p{line-height:1.7;color:#c4b8c8}
.card{border:1px solid #493b4c;border-radius:18px;padding:24px;background:#17121b}a{color:#f3d36a}
</style></head><body><small>LOCAL STAGING · NOT PUBLIC</small><h1>Your next website starts here.</h1>
<div class="card"><p>This is a private starter page served from Termux on this device. Edit <code>~/lucifer-sites/static/index.html</code> to customize it.</p>
<p>No analytics, external scripts, sign-in forms, or data collection are included.</p></div></body></html>"""

    private val apiHtml = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lucifer API · Local Preview</title><style>
:root{color-scheme:dark;font:16px system-ui;background:#0c0a10;color:#f4edf5}body{max-width:760px;margin:12vh auto;padding:24px}
small{color:#d9b942;letter-spacing:.16em}h1{font-size:clamp(2rem,7vw,4rem)}p{line-height:1.7;color:#c4b8c8}
.card{border:1px solid #493b4c;border-radius:18px;padding:24px;background:#17121b}button{background:#8b0000;color:white;border:0;border-radius:8px;padding:12px 18px}
pre{white-space:pre-wrap;color:#b8ffd8}</style></head><body><small>LOCAL API STAGING · NOT PUBLIC</small><h1>Website + API starter</h1>
<div class="card"><p>The frontend is served by a small Python standard-library server. <code>/health</code> reports health and <code>/api/message</code> is a read-only example endpoint.</p>
<button id="check">Check API</button><pre id="result">Ready</pre></div>
<script>document.querySelector('#check').onclick=async()=>{const r=await fetch('/api/message');document.querySelector('#result').textContent=await r.text()}</script>
</body></html>"""

    private val apiPython = """from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json

ROOT = Path.home() / "lucifer-sites" / "api"

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            body = json.dumps({"ok": True, "service": "lucifer-local-api"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
        elif self.path == "/api/message":
            body = json.dumps({"message": "The local API is running.", "public": False}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
        elif self.path in ("/", "/index.html"):
            body = (ROOT / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
        else:
            body = b"not found"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[http] " + (fmt % args))

print("Serving private preview at http://127.0.0.1:8766", flush=True)
HTTPServer(("127.0.0.1", 8766), Handler).serve_forever()
"""

    private val paymentDemoHtml = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>USDC Checkout · Demo</title><style>
:root{color-scheme:dark;font:16px system-ui;background:#0c0a10;color:#f4edf5}body{max-width:760px;margin:8vh auto;padding:24px}
small{color:#d9b942;letter-spacing:.14em}h1{font-size:clamp(2rem,7vw,4rem)}p{line-height:1.7;color:#c4b8c8}
.card{border:1px solid #493b4c;border-radius:18px;padding:22px;margin:16px 0;background:#17121b}
button{background:#8b0000;color:white;border:0;border-radius:8px;padding:12px 18px;margin:6px 6px 6px 0}
.notice{color:#ffd76a;font-weight:700}pre{white-space:pre-wrap;color:#b8ffd8}
</style></head><body><small>LOCAL PAYMENT PROTOTYPE · NO MONEY MOVES</small><h1>USDC checkout preview</h1>
<p class="notice">DEMO ONLY — no wallet is connected, no quote is fetched, and no payment or charge can be created.</p>
<div class="card"><h2>Settlement target</h2><p>Arbitrum · USDC</p><p id="config">Checking local demo configuration…</p></div>
<div class="card"><h2>Pay with crypto</h2><p>A future checkout can show supported wallet assets and a time-limited swap quote into USDC. The payer would review and sign in their own wallet.</p>
<button id="crypto">Preview crypto option</button></div>
<div class="card"><h2>Pay by card or bank</h2><p>A future hosted provider would handle payment details, eligibility checks, and fiat conversion.</p>
<button id="fiat">Preview card/bank option</button></div>
<pre id="result">No payment has been attempted.</pre>
<script>
async function preview(path){const r=await fetch(path,{method:'POST'});const data=await r.json();document.querySelector('#result').textContent=data.message}
document.querySelector('#crypto').onclick=()=>preview('/api/demo/crypto-quote');
document.querySelector('#fiat').onclick=()=>preview('/api/demo/fiat-checkout');
fetch('/api/payment-config').then(r=>r.json()).then(c=>document.querySelector('#config').textContent=c.message).catch(()=>document.querySelector('#config').textContent='Demo API unavailable.');
</script></body></html>"""

    private val paymentDemoPython = """from http.server import BaseHTTPRequestHandler, HTTPServer
import json

PAGE = open(__file__.replace("server.py", "index.html"), "rb").read()

class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload, content_type="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/checkout"):
            self.reply(200, PAGE, "text/html; charset=utf-8")
        elif self.path == "/health":
            self.reply(200, {"ok": True, "mode": "demo"})
        elif self.path == "/api/payment-config":
            self.reply(200, {"enabled": False, "network": "arbitrum", "asset": "USDC", "recipientConfigured": False, "message": "Demo only. No real payment, quote, wallet, or card/bank provider is configured."})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path in ("/api/demo/crypto-quote", "/api/demo/fiat-checkout"):
            self.reply(503, {"enabled": False, "message": "Payments are disabled in this demo. No quote, wallet request, transaction, or charge was created."})
        else:
            self.reply(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        print("[checkout-demo] " + (fmt % args))

print("Serving no-money checkout demo at http://127.0.0.1:8767", flush=True)
HTTPServer(("127.0.0.1", 8767), Handler).serve_forever()
"""

    suspend fun stageStatic(context: Context): List<String> = stage(
        context,
        name = "static",
        session = STATIC_SESSION,
        port = STATIC_PORT,
        files = mapOf("index.html" to staticHtml)
    )

    suspend fun stageApi(context: Context): List<String> = stage(
        context,
        name = "api",
        session = API_SESSION,
        port = API_PORT,
        files = mapOf("index.html" to apiHtml, "server.py" to apiPython)
    )

    suspend fun stagePaymentDemo(context: Context): List<String> = stage(
        context,
        name = "payment-demo",
        session = PAYMENT_SESSION,
        port = PAYMENT_PORT,
        files = mapOf("index.html" to paymentDemoHtml, "server.py" to paymentDemoPython)
    )

    suspend fun stop(context: Context): List<String> {
        val result = TerminalExecutor(context.applicationContext).executeTermuxBackground(
            "for s in $STATIC_SESSION $API_SESSION $PAYMENT_SESSION; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done; echo '[INFO] Local website preview sessions stopped.'",
            timeoutMillis = 15_000L
        )
        return result
    }

    private suspend fun stage(
        context: Context,
        name: String,
        session: String,
        port: Int,
        files: Map<String, String>
    ): List<String> {
        val appContext = context.applicationContext
        val executor = TerminalExecutor(appContext)
        val root = "\$HOME/lucifer-sites/$name"
        val writeFiles = files.entries.joinToString(" && ") { (fileName, content) ->
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val python = "from pathlib import Path; import base64; p=Path.home()/'lucifer-sites'/'$name'; p.mkdir(parents=True, exist_ok=True); f=p/'$fileName'; f.write_bytes(base64.b64decode('$encoded')) if not f.exists() else None"
            "python -c ${shellQuote(python)}"
        }
        val startServer = if (name == "static") {
            "python -m http.server $port --bind 127.0.0.1"
        } else {
            "python $root/server.py"
        }
        val probePath = if (name == "api" || name == "payment-demo") "/health" else "/"
        val probe = "import urllib.request; urllib.request.urlopen('http://127.0.0.1:$port$probePath', timeout=3).read()"
        val command = "set -e; command -v python >/dev/null 2>&1 || { echo '[SETUP] Install Python in Termux first (INSTALL TOOLKIT).'; exit 127; }; " +
            "$writeFiles; (tmux kill-session -t ${shellQuote(session)} 2>/dev/null || true); " +
            "tmux new-session -d -s ${shellQuote(session)} -c \"$root\" ${shellQuote(startServer)}; sleep 1; " +
            "if ! python -c ${shellQuote(probe)}; then tmux kill-session -t ${shellQuote(session)} 2>/dev/null || true; echo '[ERROR] Local preview health check failed.'; exit 1; fi; " +
            "echo ${shellQuote("[SUCCESS] Local $name preview: http://127.0.0.1:$port")}"
        return executor.executeTermuxBackground(command, timeoutMillis = 15_000L)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
