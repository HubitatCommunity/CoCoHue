#!/bin/sh
# Off-hub tests for the CoCoHue Bridge driver. Needs Java 8+ and Groovy 2.4 (the line the hub runs):
# uses `groovy` if on PATH, else the groovy-all jar in $GROOVY_JAR.
#   tests/run.sh             everything
#   tests/run.sh chunk       only tests whose name contains "chunk"
set -eu
TESTS="$(cd "$(dirname "$0")" && pwd)"
DRIVER="$TESTS/../drivers/cocohue-bridge-driver.groovy"
if command -v groovy >/dev/null 2>&1; then
    exec groovy "$TESTS/bridge-tests.groovy" "$DRIVER" "$@"
fi
JAR="${GROOVY_JAR:-$HOME/.cache/hubitat-groovy/groovy-all-2.4.21.jar}"
[ -f "$JAR" ] || { echo "No groovy on PATH and no jar at $JAR (set GROOVY_JAR)" >&2; exit 2; }
exec java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/bridge-tests.groovy" "$DRIVER" "$@"
