"""Host-side protocol smoke test for the lean-audit MCP server (Python is test-only).

Mirrors mcp/test_mcp_protocol.py: boots the FastMCP server on a random loopback port with a
bearer token, then drives the MCP handshake (initialize -> notifications/initialized ->
tools/list -> tools/call) over HTTP, the same way LeanAuditMcpClient.kt does on device.

Run:  python3 mcp/test_lean_audit_mcp.py
Skips cleanly when the server source or its deps (fastmcp, mcp) are unavailable.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SERVER = Path(os.environ.get(
    "LEAN_AUDIT_MCP_SERVER",
    Path.home() / "lean-audit-mcp" / "src" / "lean_audit_mcp" / "server.py",
))
TOKEN = os.environ.get("LEAN_AUDIT_MCP_TOKEN", "smoke-test-" + "x" * 40)


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class _Rpc:
    def __init__(self, url: str, token: str):
        self.url, self.token, self.next_id, self.session = url, token, 1, None

    def post(self, payload: dict) -> dict:
        req = urllib.request.Request(
            self.url,
            data=json.dumps(payload).encode(),
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                **({"MCP-Session-Id": self.session} if self.session else {}),
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            self.session = resp.headers.get("MCP-Session-Id") or self.session
            body = resp.read().decode()
        body = body.strip()
        if not body:
            return {}
        if body.startswith("{"):
            return json.loads(body)
        for line in body.splitlines():
            if line.startswith("data:") and line[5:].strip().startswith("{"):
                return json.loads(line[5:].strip())
        return {}

    def call(self, method: str, params: dict | None = None):
        payload = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        self.next_id += 1
        if params is not None:
            payload["params"] = params
        return self.post(payload)

    def notify(self, method: str):
        self.post({"jsonrpc": "2.0", "method": method})


class LeanAuditMcpSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SERVER.is_file():
            raise unittest.SkipTest(f"lean-audit server not found: {SERVER}")
        if shutil.which("python3") is None:
            raise unittest.SkipTest("no python3")
        try:
            import fastmcp  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("fastmcp not installed; pip install -r lean-audit-mcp/requirements.txt")
        cls.port = _free_port()
        cls.url = f"http://127.0.0.1:{cls.port}/mcp"
        env = os.environ.copy()
        env["LEAN_AUDIT_MCP_TOKEN"] = TOKEN
        env["LEAN_AUDIT_MCP_PORT"] = str(cls.port)
        cls.log = tempfile.TemporaryFile(mode="w+b")
        cls.proc = subprocess.Popen(
            [sys.executable, str(SERVER), "--http"],
            env=env, stdout=cls.log, stderr=subprocess.STDOUT,
        )
        for _ in range(200):
            if cls.proc.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError("server exited during startup: " + cls.log.read().decode(errors="replace"))
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{cls.port}/health", timeout=0.2) as resp:
                    if resp.status == 200:
                        break
            except Exception:
                time.sleep(0.05)
        else:
            cls.proc.terminate()
            raise RuntimeError("server did not become healthy")

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "proc", None) is not None:
            cls.proc.terminate()
            try:
                cls.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.proc.kill()
        if getattr(cls, "log", None) is not None:
            cls.log.close()

    def setUp(self):
        self.rpc = _Rpc(self.url, TOKEN)
        init = self.rpc.call("initialize", {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "0.1"},
        })
        self.assertIn("result", init, f"initialize failed: {init}")
        self.rpc.notify("notifications/initialized")

    def test_tools_list_exposes_lean_tools(self):
        result = self.rpc.call("tools/list")
        names = {t["name"] for t in result["result"]["tools"]}
        expected = {"lean_check", "lean_axioms", "lean_audit_corpus",
                    "lean_explain_failure", "lean_verify_claim", "lean_audit_report"}
        self.assertTrue(expected.issubset(names), f"missing tools: {expected - names}; got {names}")

    def test_bad_token_rejected(self):
        bad = _Rpc(self.url, "wrong-token-wrong-token-wrong")
        try:
            bad.call("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                    "clientInfo": {"name": "x", "version": "0"}})
            self.fail("expected HTTP error for bad token")
        except urllib.error.HTTPError as err:
            self.assertIn(err.code, (401, 403))

    def test_loopback_only_shape(self):
        # The client on device only accepts http://127.0.0.1:8931/mcp or localhost — same shape here.
        self.assertTrue(self.url.startswith("http://127.0.0.1:") and self.url.endswith("/mcp"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
