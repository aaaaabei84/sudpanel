#!/usr/bin/env bash
# Build source only. Does not read local configuration/data or bundle ADB/JDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
MVN="${MVN:-$(command -v mvn || true)}"
NPM="${NPM:-$(command -v npm || true)}"
NODE="${NODE:-$(command -v node || true)}"
for tool in "$JAVA_BIN" "$MVN" "$NPM" "$NODE"; do
  [ -n "$tool" ] && command -v "$tool" >/dev/null 2>&1 || { echo "Missing Java, Maven, Node or npm; configure PATH or the corresponding override." >&2; exit 1; }
done
JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && [ "$JAVA_MAJOR" -ge 21 ] || { echo 'Java 21 or newer is required.' >&2; exit 1; }
"$NODE" -e 'const [major,minor]=process.versions.node.split(".").map(Number);if(!((major===20&&minor>=19)||(major===22&&minor>=12)||major>22)){console.error("Node 20.19+ or 22.12+ is required; Node 22.12+ is recommended.");process.exit(1)}'
[ ! -e build/seed-staging ] || { echo 'Refusing a build directory containing a staged database.' >&2; exit 1; }

"$NPM" --prefix frontend ci --ignore-scripts
"$NPM" --prefix frontend run build
[ -f src/main/resources/static/index.html ] || { echo 'Frontend output is missing.' >&2; exit 1; }
"$MVN" -B clean package
echo 'JAR built in target/. Maven tests completed. ADB, JDK, user data and native application bundles were not packaged by this script.'
