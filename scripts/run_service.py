"""Run an immutable copy of a built JAR: subsequent builds cannot replace a live classpath."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
if len(sys.argv) < 2 or sys.argv[1] not in ("backend", "central-mock"):
    raise SystemExit("Usage: python3 scripts/run_service.py backend|central-mock [Spring arguments]")
module = sys.argv[1]
source = root / module / "target" / (module + "-0.1.0-SNAPSHOT.jar")
if not source.is_file():
    raise SystemExit("Build first: ./mvnw verify")
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
with tempfile.TemporaryDirectory(prefix="cielo-" + module + "-") as directory:
    executable = Path(directory) / source.name
    shutil.copy2(source, executable)
    process = subprocess.Popen([java, "-jar", str(executable), *sys.argv[2:]], cwd=root)
    try:
        raise SystemExit(process.wait())
    except KeyboardInterrupt:
        process.terminate()
        process.wait(timeout=20)
