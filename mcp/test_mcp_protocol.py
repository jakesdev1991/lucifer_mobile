"""Loopback Streamable HTTP/MCP integration tests against a temporary memory fixture."""
import asyncio
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

ROOT = Path(__file__).resolve().parent


class McpProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.memory_file = Path(cls.temp_dir.name) / "memory.md"
        cls.memory_file.write_text(
            "# Permission boundary\n\nNever change PMX grants. Keep package and AppOps inspection read-only.\n",
            encoding="utf-8",
        )
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        cls.token = "protocol-test-" + "m" * 40
        environment = os.environ.copy()
        environment.update({
            "LUCIFER_MEMORY_MCP_TOKEN": cls.token,
            "LUCIFER_MEMORY_LOCAL_FILE": str(cls.memory_file),
            "LUCIFER_MEMORY_MCP_PORT": str(cls.port),
            "LUCIFER_ALBERT_ENABLED": "0",
        })
        cls.process = subprocess.Popen(
            [sys.executable, str(ROOT / "drive_memory_server.py")],
            cwd=str(ROOT), env=environment,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True,
        )
        cls.url = f"http://127.0.0.1:{cls.port}/mcp"
        for _ in range(100):
            if cls.process.poll() is not None:
                error = cls.process.stderr.read() if cls.process.stderr else ""
                raise RuntimeError(f"MCP server exited during startup: {error}")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{cls.port}/health", timeout=0.2) as response:
                    if response.status == 200:
                        break
            except Exception:
                time.sleep(0.05)
        else:
            cls.process.terminate()
            raise RuntimeError("MCP server did not become healthy")

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "process", None) is not None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)
        if getattr(cls, "temp_dir", None) is not None:
            cls.temp_dir.cleanup()

    def test_requires_bearer_token(self):
        request = urllib.request.Request(self.url, data=b"{}", method="POST")
        try:
            urllib.request.urlopen(request, timeout=2)
            self.fail("unauthenticated MCP request unexpectedly succeeded")
        except urllib.error.HTTPError as error:
            self.assertEqual(error.code, 401)

    def test_standard_mcp_handshake_and_read_only_tools(self):
        async def exercise():
            async with httpx.AsyncClient(
                headers={"Authorization": f"Bearer {self.token}"}, timeout=5
            ) as http_client:
                async with streamable_http_client(self.url, http_client=http_client) as (read_stream, write_stream, _):
                    async with ClientSession(read_stream, write_stream) as session:
                        initialized = await session.initialize()
                        self.assertEqual(initialized.serverInfo.name, "Lucifer Drive Memory")
                        result = await session.call_tool(
                            "search_memory", {"query": "PMX grants read-only", "limit": 2}
                        )
                        self.assertTrue(result.content)
                        self.assertIn("Never change PMX grants", result.content[0].text)
                        status = await session.call_tool("memory_status", {})
                        self.assertIn("Google Drive via rclone", status.content[0].text)
                        tools = await session.list_tools()
                        names = {tool.name for tool in tools.tools}
                        self.assertEqual(names, {"search_memory", "memory_status", "reload_memory"})
        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
