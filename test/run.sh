#!/usr/bin/env bash
# Compiles the plugin's pure sources (everything that does not touch droidtop's plugin API) with test/Tests.kt and
# runs it. Needs kotlinc and java on PATH. org.json (Android has it built in) comes from Maven Central, pinned by
# SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test
JSON=build/test/json-20240303.jar
if [ ! -f "$JSON" ]; then
  curl -sfL https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON"
fi
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $JSON" | sha256sum -c --quiet -
D=src/dev/droidtop/plugins/githubapps
kotlinc -jvm-target 17 -cp "$JSON" -include-runtime -d build/test/tests.jar \
  $D/Versions.kt $D/Assets.kt $D/Forges.kt $D/HttpAnswer.kt $D/ApkProbe.kt $D/ObtainiumImport.kt test/Tests.kt
java -cp "build/test/tests.jar:$JSON" dev.droidtop.plugins.githubapps.TestsKt
