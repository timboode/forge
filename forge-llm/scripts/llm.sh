#!/usr/bin/env bash
# Launches the forge-llm tools (Linux, macOS, Git Bash).
#
#   scripts/llm.sh play  [options]   play a real game in the Forge window against LLM opponents
#   scripts/llm.sh run   [options]   headless games (statistics, transcripts)
#   scripts/llm.sh check [options]   check that opencode and the model respond
#
# Build first (from the repository root):  mvn -Pllm -pl forge-llm -am compile
# Needs a Java 17+ runtime: JAVA_HOME is used when set, otherwise "java" from the PATH.
# Options are described in forge-llm/README.md and in the javadoc of forge.llm.run.RunOptions.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f target/classpath.txt ]; then
    echo "target/classpath.txt not found. Build first, from the repository root:" >&2
    echo "    mvn -Pllm -pl forge-llm -am compile" >&2
    exit 1
fi

case "${1:-}" in
    play)  MAIN=forge.llm.run.PlayVsLlm ;;
    run)   MAIN=forge.llm.run.LlmMatchRunner ;;
    check) MAIN=forge.llm.run.OpencodeCheck ;;
    *) echo "Usage: $0 <play|run|check> [options]" >&2; exit 2 ;;
esac
shift

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi

# The generated opencode config asks for the OpenRouter key as {env:OPENROUTER_API_KEY}. The isolated server
# forge-llm starts cannot read opencode's own auth store, so take the key from the local opencode installation
# when it is not already in the environment (it travels to opencode in the environment, never to disk).
if [ -z "${OPENROUTER_API_KEY:-}" ] && command -v node >/dev/null 2>&1; then
    OPENROUTER_API_KEY="$(node -e 'try { const a = require(process.env.HOME + "/.local/share/opencode/auth.json"); if (a.openrouter && a.openrouter.key) process.stdout.write(a.openrouter.key) } catch {}' 2>/dev/null || true)"
    if [ -n "${OPENROUTER_API_KEY:-}" ]; then
        export OPENROUTER_API_KEY
        echo 'Using the OpenRouter credential from the local opencode installation.'
    fi
fi

# classpath.txt uses the separator of the platform that built it (';' on Windows, ':' elsewhere)
exec "$JAVA" -Xmx3g -Dfile.encoding=UTF-8 -Dio.netty.tryReflectionSetAccessible=true \
    --add-opens java.desktop/java.beans=ALL-UNNAMED --add-opens java.desktop/javax.swing.border=ALL-UNNAMED \
    --add-opens java.desktop/javax.swing.event=ALL-UNNAMED --add-opens java.desktop/sun.swing=ALL-UNNAMED \
    --add-opens java.desktop/java.awt.image=ALL-UNNAMED --add-opens java.desktop/java.awt.color=ALL-UNNAMED \
    --add-opens java.desktop/sun.awt.image=ALL-UNNAMED --add-opens java.desktop/javax.swing=ALL-UNNAMED \
    --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED \
    -cp "target/classes$(case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) echo ';';; *) echo ':';; esac)$(cat target/classpath.txt)" \
    "$MAIN" "$@"
