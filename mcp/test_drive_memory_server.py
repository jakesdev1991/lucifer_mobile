"""Fast local tests for the read-only memory index; run with `python -m unittest`."""
import os
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("LUCIFER_MEMORY_MCP_TOKEN", "unit-test-token-" + "x" * 40)
os.environ.setdefault("LUCIFER_ALBERT_ENABLED", "0")

from drive_memory_server import MemoryIndex, redact_secret_lines, split_memory, tokenize  # noqa: E402


class MemoryIndexTests(unittest.TestCase):
    def test_chunks_keep_headings_and_line_numbers(self):
        chunks = split_memory("# Preferences\n\nUse read-only access.\n\n# Project\n\nPixel build is unknown.\n")
        self.assertEqual([chunk.heading for chunk in chunks], ["Preferences", "Project"])
        self.assertEqual(chunks[0].start_line, 1)
        self.assertEqual(chunks[1].start_line, 5)

    def test_retrieval_uses_drive_fixture_and_returns_matching_passage(self):
        with tempfile.TemporaryDirectory() as directory:
            file_path = Path(directory) / "Lucifer_Memory.md"
            file_path.write_text(
                "# Permission boundary\n\nNever change PMX grants. Inspect package and AppOps state read-only.\n\n"
                "# Website milestone\n\nCreate a local-only static site and API starter.\n",
                encoding="utf-8",
            )
            index = MemoryIndex()
            index.source.local_path = str(file_path)
            result = index.search("Permission Manager X grants", limit=3)
            self.assertTrue(result)
            self.assertEqual(result[0][0].heading, "Permission boundary")
            self.assertIn("BM25 lexical retrieval", result[0][1])
            self.assertEqual(index.status()["chunks"], 2)

    def test_unmatched_query_does_not_return_arbitrary_passages(self):
        with tempfile.TemporaryDirectory() as directory:
            file_path = Path(directory) / "Lucifer_Memory.md"
            file_path.write_text("# Small corpus\n\nPixel diagnostics are local and read-only.\n", encoding="utf-8")
            index = MemoryIndex()
            index.source.local_path = str(file_path)
            self.assertEqual(index.search("quantum pineapple submarine", limit=5), [])

    def test_secret_like_lines_are_redacted_before_indexing(self):
        redacted = redact_secret_lines("NVIDIA API key: nvapi-abcdefghijklmnop\nKeep this safe fact.")
        self.assertNotIn("nvapi-abcdefghijklmnop", redacted)
        self.assertIn("Keep this safe fact.", redacted)

    def test_tokenizer_removes_common_stopwords(self):
        terms = tokenize("What is the current Pixel OS build?")
        self.assertIn("pixel", terms)
        self.assertIn("build", terms)
        self.assertNotIn("the", terms)


if __name__ == "__main__":
    unittest.main()
