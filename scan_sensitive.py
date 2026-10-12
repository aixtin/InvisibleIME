#!/usr/bin/env python3
"""
scan_sensitive.py — Pre-publish sensitive-information scanner.

Scans a repository for hardcoded secrets, tokens, private IPs, credentials,
and known infrastructure identifiers before pushing to GitHub.

Usage:
    python3 scan_sensitive.py [repo_root] [--json]

Exit code 0 = clean (or only whitelisted hits), 1 = findings that need review.
"""

import argparse
import fnmatch
import json
import os
import re
import sys

# Files/dirs that are skipped entirely (binary, vendor, VCS, build).
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", ".idea", ".vscode", "keystore", "signing"}
SKIP_PATTERNS = ["*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.apk", "*.aab",
                 "*.jar", "*.aar", "*.dex", "*.so", "*.keystore", "*.jks",
                 "*.pem", "*.key", "*.pk8", "*.lock", "*.class"]

# ---- Rules: (name, regex) ----
# Order matters: more specific rules first to avoid noisy overlaps.
RULES = [
    ("DebugServer token pattern",
     r"(?i)(x-auth-token|auth[_-]?token|api[_-]?key|access[_-]?token)\s*[:=]\s*['\"]?[A-Za-z0-9_\-\.]{8,}['\"]?"),
    ("Password in code",
     r"(?i)(password|passwd|pwd|secret)\s*[:=]\s*['\"][^'\"]{4,}['\"]"),
    ("Private IP (10.x / 192.168.x / 172.16-31.x)",
     r"\b(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b"),
    ("Known VPS / server IP (public)",
     r"\b(8\.137\.61\.113)\b"),
    ("SSH / tunnel port markers",
     r"\b(61113|2222|8765|8767|8899|18765)\b"),
    ("WireGuard / VPN identifiers",
     r"(?i)(wireguard|wg0|allowedips|endpoint\s*=\s*\d+\.\d+\.\d+\.\d+)"),
    ("SSH private key header",
     r"-----BEGIN (RSA|EC|OPENSSH|DSA) PRIVATE KEY-----"),
    ("Shared prefs / data path leak",
     r"(?i)(/data/data/|shared_prefs|debug_server\.xml)"),
    ("Base64 blob suspicious length (likely token)",
     r"base64:type252:"),
]

# Whitelist: (fnmatch pattern, rule name) — files where a rule is expected.
WHITELIST = [
    ("scan_sensitive.py", "SSH / tunnel port markers"),   # self-reference
    ("README.md", "SSH / tunnel port markers"),           # documentation may mention ports
]


def scan_file(path, root):
    findings = []
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
    except OSError:
        return findings
    for line_no, line in enumerate(content.splitlines(), 1):
        for name, pattern in RULES:
            m = re.search(pattern, line)
            if m:
                rel = os.path.relpath(path, root)
                if any(fnmatch.fnmatch(rel, pat) and name == rname for pat, rname in WHITELIST):
                    continue
                findings.append({
                    "file": rel,
                    "line": line_no,
                    "rule": name,
                    "match": m.group(0)[:120],
                })
    return findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=".", help="repo root to scan")
    ap.add_argument("--json", action="store_true", help="emit JSON")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    all_findings = []
    scanned = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fname in filenames:
            if any(fnmatch.fnmatch(fname, p) for p in SKIP_PATTERNS):
                continue
            fpath = os.path.join(dirpath, fname)
            if os.path.getsize(fpath) > 2 * 1024 * 1024:  # skip >2MB
                continue
            scanned += 1
            all_findings.extend(scan_file(fpath, root))

    if args.json:
        print(json.dumps({"scanned": scanned, "findings": all_findings}, indent=2, ensure_ascii=False))
    else:
        print(f"Scanned {scanned} files.")
        if not all_findings:
            print("CLEAN — no sensitive hits.")
            sys.exit(0)
        print(f"{len(all_findings)} finding(s) need review:\n")
        for f in all_findings:
            print(f"  [{f['rule']}] {f['file']}:{f['line']}  ->  {f['match']}")
        print("\nReview each hit: remove, replace with placeholder, or add to WHITELIST.")
        sys.exit(1)


if __name__ == "__main__":
    main()
