#!/usr/bin/env python3
"""Fail when the Playwright version in pom.xml, the Dockerfile base image and mcp-server/pom.xml disagree.

The Docker image ships browsers built for one Playwright release; a driver from another release
cannot launch them. Dependabot bumps pom.xml only, so this check makes the mismatch a clear CI
failure on that PR instead of a confusing browser-launch error later. Standard library only.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_version(pom: str = "pom.xml") -> str:
    node = ET.parse(ROOT / pom).getroot().find("m:properties/m:playwright.version", NS)
    if node is None or not (node.text or "").strip():
        sys.exit(f"check_versions: <playwright.version> not found in {pom}")
    return node.text.strip()


def docker_version() -> str:
    match = re.search(r"^FROM\s+mcr\.microsoft\.com/playwright/java:v(\S+?)-\w+\s*$",
                      (ROOT / "Dockerfile").read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        sys.exit("check_versions: no 'FROM mcr.microsoft.com/playwright/java:v<version>-<distro>' line in Dockerfile")
    return match.group(1)


if __name__ == "__main__":
    pom, docker = pom_version(), docker_version()
    ok = True
    if pom != docker:
        print(f"::error file=Dockerfile::Playwright version mismatch: pom.xml has {pom}, Dockerfile has {docker}. "
              f"Change the Dockerfile FROM line to mcr.microsoft.com/playwright/java:v{pom}-noble in this PR.")
        ok = False
    if (ROOT / "mcp-server/pom.xml").is_file():
        mcp = pom_version("mcp-server/pom.xml")
        if mcp != pom:
            print(f"::error file=mcp-server/pom.xml::Playwright version mismatch: pom.xml has {pom}, mcp-server/pom.xml has {mcp}. "
                  f"Set <playwright.version>{pom}</playwright.version> in mcp-server/pom.xml in this PR.")
            ok = False
    if not ok:
        sys.exit(1)
    print(f"Playwright {pom}: pom.xml, Dockerfile and mcp-server/pom.xml agree.")
