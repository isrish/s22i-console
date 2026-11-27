import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("publication", Path(__file__).resolve().parents[1] / "scripts/check_publication.py")
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class PublicationTests(unittest.TestCase):
    def test_source_and_synthetic_values_are_allowed(self):
        self.assertEqual([], publication.findings("app/src/dev/s22i/console/Example.java", b'package demo; class Example { int field = 26; String url = "https://example.org"; }'))

    def test_private_artifacts_and_links_are_rejected(self):
        for path in ("devices/capture.json", "config/bike.json", ".cache/key.keystore", "reference/source.java", "build/app.apk", ".env", ".local-private/notes.txt", "app/res/../secret.xml"):
            with self.subTest(path=path):
                self.assertIn("path outside the public source allowlist", publication.findings(path, b"{}"))
        self.assertIn("non-regular file", publication.findings("README.md", b"target", "120000"))

    def test_identifiers_are_detected_without_echoing_them(self):
        samples = {
            "personal home path": "/" + "Users" + "/example/project",
            "IPv4 address": ".".join(["192", "0", "2", "7"]),
            "MAC address": ":".join(["ab"] * 6),
            "access token": "ghp_" + "x" * 30,
            "private key": "-----BEGIN " + "PRIVATE KEY-----",
            "email address": "example" + "@" + "example.org",
        }
        for label, value in samples.items():
            with self.subTest(label=label):
                result = publication.findings("README.md", value.encode())
                self.assertIn(label, result)
                self.assertNotIn(value, " ".join(result))

    def test_comments_and_binary_are_rejected(self):
        for path, content in (("app/src/dev/s22i/console/Example.java", b"/" + b"/ note"), ("scripts/example.py", b"# note"), ("scripts/example.py", b'"""note"""'), ("app/res/values/example.xml", b"<!" + b"-- note -->")):
            with self.subTest(path=path):
                self.assertTrue(publication.findings(path, content))
        self.assertEqual([], publication.findings("scripts/example.py", b"#!/usr/bin/env python3\nprint('ok')\n"))
        self.assertIn("binary file", publication.findings("README.md", b"\xff"))

    def test_legal_attribution_email_is_preserved(self):
        email = ("copyright" + "@" + "example.org").encode()
        self.assertEqual([], publication.findings("app/assets/licenses/Example.txt", email))


if __name__ == "__main__":
    unittest.main()
