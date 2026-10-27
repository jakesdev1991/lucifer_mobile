"""Host-side MCP protocol tests for the native C++ Termux server; Python is test-only."""
import asyncio
import os
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import unittest
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

ROOT = Path(__file__).resolve().parent
CPP_BINARY = Path(os.environ.get("LUCIFER_MCP_CPP_BINARY", ROOT / "build" / "lucifer_memory_mcp"))


class McpProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CPP_BINARY.is_file():
            raise unittest.SkipTest(f"Build native C++ server first; binary not found: {CPP_BINARY}")
        cls.temp_dir = tempfile.TemporaryDirectory()
        temp = Path(cls.temp_dir.name)
        cls.memory_file = temp / "memory.md"
        cls.memory_file.write_text(
            "# Permission boundary\n\nNever change PMX grants. Keep package and AppOps inspection read-only.\n"
            "NVIDIA API key: nvapi-abcdefghijklmnop\n",
            encoding="utf-8",
        )
        fake_bin = temp / "bin"
        fake_bin.mkdir()
        fake_rclone = fake_bin / "rclone"
        fake_rclone.write_text("#!/bin/sh\ncat \"$LUCIFER_MEMORY_FIXTURE\"\n", encoding="utf-8")
        fake_rclone.chmod(0o700)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        cls.token = "protocol-test-" + "m" * 40
        environment = os.environ.copy()
        environment.update({
            "LUCIFER_MEMORY_MCP_TOKEN": cls.token,
            "LUCIFER_MEMORY_RCLONE_PATH": "fixture:memory.md",
            "LUCIFER_MEMORY_MCP_PORT": str(cls.port),
            "LUCIFER_MEMORY_FIXTURE": str(cls.memory_file),
            "PATH": str(fake_bin) + os.pathsep + environment.get("PATH", ""),
            "LUCIFER_EMBEDDING_MODEL": "fixture-only",
        })
        cls.log_file = open(temp / "server.log", "w+b")
        cls.process = subprocess.Popen([str(CPP_BINARY)], cwd=str(ROOT), env=environment,
                                       stdout=cls.log_file, stderr=subprocess.STDOUT)
        cls.url = f"http://127.0.0.1:{cls.port}/mcp"
        for _ in range(100):
            if cls.process.poll() is not None:
                cls.log_file.seek(0)
                raise RuntimeError(f"C++ MCP server exited during startup: {cls.log_file.read().decode(errors='replace')}")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{cls.port}/health", timeout=0.2) as response:
                    if response.status == 200:
                        break
            except Exception:
                time.sleep(0.05)
        else:
            cls.process.terminate()
            raise RuntimeError("C++ MCP server did not become healthy")

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "process", None) is not None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)
        if getattr(cls, "log_file", None) is not None:
            cls.log_file.close()
        if getattr(cls, "temp_dir", None) is not None:
            cls.temp_dir.cleanup()

    def test_requires_bearer_token(self):
        request = urllib.request.Request(self.url, data=b"{}", method="POST")
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(raised.exception.code, 401)

    def test_rejects_non_loopback_origin(self):
        request = urllib.request.Request(
            self.url,
            data=b"{}",
            headers={"Authorization": f"Bearer {self.token}", "Origin": "http://localhost.attacker.example"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(raised.exception.code, 403)

    def test_standard_mcp_handshake_retrieval_and_read_only_tools(self):
        async def exercise():
            async with httpx.AsyncClient(
                headers={"Authorization": f"Bearer {self.token}"}, timeout=10
            ) as http_client:
                async with streamable_http_client(self.url, http_client=http_client) as (read_stream, write_stream, _):
                    async with ClientSession(read_stream, write_stream) as session:
                        initialized = await session.initialize()
                        self.assertEqual(initialized.serverInfo.name, "Lucifer Drive Memory")
                        result = await session.call_tool("search_memory", {"query": "PMX grants read-only", "limit": 2})
                        self.assertTrue(result.content)
                        self.assertIn("Never change PMX grants", result.content[0].text)
                        self.assertNotIn("nvapi-abcdefghijklmnop", result.content[0].text)
                        self.assertIn("untrusted reference data", result.content[0].text)
                        self.assertIn("BM25 lexical retrieval", result.content[0].text)
                        status = await session.call_tool("memory_status", {})
                        self.assertIn("Google Drive via rclone", status.content[0].text)
                        tools = await session.list_tools()
                        self.assertEqual({tool.name for tool in tools.tools}, {"search_memory", "memory_status", "reload_memory"})
        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
