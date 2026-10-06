# Launches the forge-llm tools on Windows (PowerShell). Usually started through llm.cmd.
#
#   llm play  [options]   play a real game in the Forge window against LLM opponents
#   llm run   [options]   headless games (statistics, transcripts)
#   llm check [options]   check that opencode and the model respond
#
# Build first (from the repository root):  mvn -Pllm -pl forge-llm -am compile
# Needs a Java 17+ runtime: JAVA_HOME is used when set, otherwise "java" from the PATH.
# Options are described in forge-llm/README.md and in the javadoc of forge.llm.run.RunOptions.
#
# (PowerShell rather than plain cmd because the classpath is ~10,000 characters, longer than cmd can handle.)
param(
    [Parameter(Position = 0)][string]$Mode,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$main = switch ($Mode) {
    'play'  { 'forge.llm.run.PlayVsLlm' }
    'run'   { 'forge.llm.run.LlmMatchRunner' }
    'check' { 'forge.llm.run.OpencodeCheck' }
    default { Write-Host 'Usage: llm <play|run|check> [options]'; exit 2 }
}

if (-not (Test-Path 'target/classpath.txt')) {
    Write-Host 'target/classpath.txt not found. Build first, from the repository root:'
    Write-Host '    mvn -Pllm -pl forge-llm -am compile'
    exit 1
}
$classpath = 'target/classes;' + (Get-Content 'target/classpath.txt' -Raw).Trim()
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }

$jvm = @('-Xmx3g', '-Dfile.encoding=UTF-8', '-Dio.netty.tryReflectionSetAccessible=true')
foreach ($open in @(
        'java.desktop/java.beans', 'java.desktop/javax.swing.border', 'java.desktop/javax.swing.event', 'java.desktop/sun.swing',
        'java.desktop/java.awt.image', 'java.desktop/java.awt.color', 'java.desktop/sun.awt.image', 'java.desktop/javax.swing',
        'java.desktop/java.awt', 'java.desktop/java.awt.font', 'java.base/java.util', 'java.base/java.lang',
        'java.base/java.lang.reflect', 'java.base/java.text', 'java.base/jdk.internal.misc', 'java.base/sun.nio.ch',
        'java.base/java.nio', 'java.base/java.math', 'java.base/java.util.concurrent', 'java.base/java.net')) {
    $jvm += '--add-opens'
    $jvm += "$open=ALL-UNNAMED"
}

# The generated opencode config asks for the OpenRouter key as {env:OPENROUTER_API_KEY}. The isolated server
# forge-llm starts cannot read opencode's own auth store, so take the key from the local opencode installation
# when it is not already in the environment (it travels to opencode in the environment, never to disk).
if (-not $env:OPENROUTER_API_KEY) {
    $authFile = Join-Path $HOME '.local\share\opencode\auth.json'
    if (Test-Path $authFile) {
        try {
            $auth = Get-Content $authFile -Raw | ConvertFrom-Json
            if ($auth.openrouter -and $auth.openrouter.key) {
                $env:OPENROUTER_API_KEY = $auth.openrouter.key
                Write-Host 'Using the OpenRouter credential from the local opencode installation.'
            }
        } catch {
            Write-Host "Could not read the opencode auth file ${authFile}: $_"
        }
    }
}

& $java @jvm -cp $classpath $main @Rest
exit $LASTEXITCODE
