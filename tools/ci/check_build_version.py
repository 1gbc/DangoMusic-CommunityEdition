"""Validate the build version and report whether CI may upload its JARs."""

import os
from pathlib import Path
import re
import subprocess


VERSION = re.compile(r'"[0-9]+\.[0-9]+\.[0-9]+(?P<development>-dev\.[1-9][0-9]*)?"')


def main():
    gradle = Path("build.gradle.kts").read_text(encoding="utf-8")
    declarations = re.findall(r"^version[ \t]*=[ \t]*(.*)$", gradle, re.MULTILINE)
    version = VERSION.fullmatch(declarations[0].strip()) if len(declarations) == 1 else None
    if version is None:
        raise SystemExit('build.gradle.kts must declare one version such as "0.5.4" or "0.5.4-dev.2"')

    jars = subprocess.check_output(
        ["git", "ls-files", "--", ":(top,glob)*.jar"], encoding="utf-8"
    ).strip()
    if jars:
        raise SystemExit(f"Remove packaged JARs from the repository root:\n{jars}")

    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        release = "true" if version["development"] is None else "false"
        with Path(output).open("a", encoding="utf-8") as stream:
            print(f"release={release}", file=stream)


if __name__ == "__main__":
    main()
