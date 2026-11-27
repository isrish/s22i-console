#!/usr/bin/env python3
import ast
import hashlib
import io
from pathlib import PurePosixPath
import re
import subprocess
import sys
import tokenize


ROOT_FILES = {
    "README.md", "AGENTS.md", "CONTRIBUTING.md", "LICENSE", ".gitignore",
    "docs/PROTOCOL.md", "docs/assets/icon-prompt.txt", ".github/workflows/ci.yml",
    ".github/pull_request_template.md", ".github/ISSUE_TEMPLATE/feature_request.yml",
    ".github/ISSUE_TEMPLATE/bug_report.yml", ".github/ISSUE_TEMPLATE/config.yml",
}
REVIEWED_IMAGES = {
    "docs/assets/dashboard.png": "5c45f603892669b6bfe0543194a50d4c01e57752c51913f72ba7363119d43804",
    "docs/assets/ride-summary.png": "881c80221609a6b1366c4ea5b444366e9eb2017b32ab1f9dd2d8b02cadde110d",
    "docs/assets/s22i-icon.png": "b6fbc3be24d62342b49bc043f9f2b458a5a0741462a1edf90c317adcce912340",
}
PATTERNS = {
    "personal home path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+|[A-Za-z]:\\Users\\", re.I),
    "IPv4 address": re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])"),
    "MAC address": re.compile(r"(?<![\w:])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![\w:])", re.I),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    "access token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b"),
    "email address": re.compile(r"[A-Za-z0-9_.+%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
}
JAVA_TOKENS = re.compile(r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|//[^\n]*|/\*[\s\S]*?\*/''')


def allowed(path):
    p = PurePosixPath(path)
    if path in ROOT_FILES or path in REVIEWED_IMAGES:
        return True
    if ".." in p.parts:
        return False
    return ((path.startswith("app/src/dev/s22i/console/") and p.suffix == ".java")
            or (path.startswith("tests/java/dev/s22i/console/") and p.suffix == ".java")
            or (path.startswith("app/res/") and p.suffix == ".xml")
            or path == "app/AndroidManifest.xml"
            or (path.startswith("app/assets/licenses/") and p.suffix == ".txt")
            or (p.parent.as_posix() in {"scripts", "tests"} and p.suffix == ".py"))


def findings(path, data, mode="100644"):
    issues = []
    if mode not in {"100644", "100755"}:
        issues.append("non-regular file")
    if not allowed(path):
        issues.append("path outside the public source allowlist")
    if path in REVIEWED_IMAGES:
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or hashlib.sha256(data).hexdigest() != REVIEWED_IMAGES[path]:
            issues.append("image differs from the visually reviewed public asset")
        return issues
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return issues + ["binary file"]
    if "\x00" in text:
        issues.append("binary content")
    legal = path == "LICENSE" or path.startswith("app/assets/licenses/")
    for label, pattern in PATTERNS.items():
        if label == "email address" and legal:
            continue
        if pattern.search(text):
            issues.append(label)
    if path.endswith(".java"):
        if any(m.group().startswith(("//", "/*")) for m in JAVA_TOKENS.finditer(text)):
            issues.append("source comment")
    elif path.endswith(".py"):
        try:
            tokens = tokenize.generate_tokens(io.StringIO(text).readline)
            if any(t.type == tokenize.COMMENT and not (t.start[0] == 1 and t.string.startswith("#!")) for t in tokens):
                issues.append("source comment")
            tree = ast.parse(text)
            if any(isinstance(n, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and ast.get_docstring(n) for n in ast.walk(tree)):
                issues.append("source docstring")
        except (SyntaxError, tokenize.TokenError):
            issues.append("invalid Python source")
    elif path.endswith(".xml") and "<!--" in text:
        issues.append("source comment")
    return issues


def main():
    records = subprocess.check_output(["git", "ls-files", "--stage", "-z"]).split(b"\0")
    failed = False
    count = 0
    for record in records:
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        mode, oid, stage = metadata.decode().split()
        path = name.decode()
        issues = ["unmerged index entry"] if stage != "0" else findings(path, subprocess.check_output(["git", "cat-file", "blob", oid]), mode)
        if issues:
            failed = True
            print(path + ": " + ", ".join(issues))
        count += 1
    if not count:
        print("No staged/tracked files to audit. Stage the intended source first.")
        return 1
    print(f"Publication check: {count} indexed files, {'FAILED' if failed else 'passed'}. Review content manually for identifiers this check cannot recognize.")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
