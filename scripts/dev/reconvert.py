#!/usr/bin/env python3
"""Reconvert an existing extract directory without running InDesign again."""

from __future__ import annotations

import argparse
import os
import subprocess
import time
from pathlib import Path
from typing import Iterable, List, Optional


REPO_ROOT = Path(__file__).resolve().parents[2]
CONVERTER_JAR = REPO_ROOT / "converter" / "target" / "idml-to-something-1.0.9-cli.jar"
CONVERSION_CONFIG = REPO_ROOT / "conversion-config.json"
DEFAULT_JAVA = Path("/opt/homebrew/opt/openjdk/bin/java")


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if all(ch.isalnum() or ch in "._/-:=+" for ch in value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def run(cmd: List[str], *, cwd: Path = REPO_ROOT) -> None:
    print("+ " + " ".join(shell_quote(c) for c in cmd))
    subprocess.run(cmd, cwd=str(cwd), check=True)


def java_command() -> str:
    env_java = os.environ.get("JAVA")
    if env_java:
        return env_java
    if DEFAULT_JAVA.exists():
        return str(DEFAULT_JAVA)
    return "java"


def ensure_converter_built() -> None:
    if CONVERTER_JAR.exists():
        return
    run(["mvn", "-pl", "converter", "-am", "-DskipTests", "package"])


def default_output_path(extract_dir: Path) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return extract_dir.parent / "reconverted" / f"{extract_dir.name or 'extract'}-{stamp}.hwpx"


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Convert an existing extract/output.idml to HWPX.")
    parser.add_argument("--extract", type=Path, required=True, help="Existing extract directory.")
    parser.add_argument("--out", type=Path, default=None, help="Output HWPX path.")
    parser.add_argument("--open", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument(
        "--margin-guide",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Draw diagnostic margin guide lines into the converted HWPX.",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    extract_dir = args.extract
    if not extract_dir.exists():
        raise SystemExit(f"extract dir does not exist: {extract_dir}")
    idml = extract_dir / "output.idml"
    if not idml.exists():
        raise SystemExit(f"output.idml not found: {idml}")

    out = args.out or default_output_path(extract_dir)
    out.parent.mkdir(parents=True, exist_ok=True)
    ensure_converter_built()

    cmd = [
        java_command(),
        "-jar",
        str(CONVERTER_JAR),
        "--convert",
        str(idml),
        str(out),
        "--links-directory",
        str(extract_dir / "Links"),
        "--include-images",
    ]
    if args.margin_guide:
        cmd.append("--margin-guide")
    if CONVERSION_CONFIG.exists():
        cmd.extend(["--config", str(CONVERSION_CONFIG)])
    run(cmd)

    print(f"reconverted: {out}")
    if args.open:
        run(["open", str(out)])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
