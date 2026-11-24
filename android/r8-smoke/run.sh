#!/bin/bash
# The runtime smoke of the minified library: it compiles the harness, minifies it together with
# the library and the dependencies that an app carries, with the consumer rules of the android
# module, and runs it against a light wallet server. Rules that drop something the library needs
# at runtime fail here, in the build, instead of in the app of a consumer.
#
#   ./run.sh <the jar of the library>
#
# The environment: MONERO_C_LIBRARY is the library of monero-c to load, LWS_URI is the light
# wallet server (http://127.0.0.1:8443 by default), MONERO_C_TEST_DAEMON_URI is the regtest node
# that mines (http://127.0.0.1:18081 by default).
set -euo pipefail
cd "$(dirname "$0")"

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <the jar of the library>" >&2
  exit 1
fi
LIB_JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
if [ -z "${MONERO_C_LIBRARY:-}" ]; then
  echo "MONERO_C_LIBRARY is not set" >&2
  exit 1
fi
JAVA_HOME="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
JAVA="$JAVA_HOME/bin/java"

# everything the smoke downloads is pinned, so that the same tree builds the same minified jar
WORK=build
mkdir -p "$WORK"
download() {
  if [ ! -f "$WORK/$2" ]; then
    curl -fsSL -o "$WORK/$2" "$1"
  fi
}
MVN=https://repo1.maven.org/maven2
download $MVN/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/kotlin-compiler-embeddable-2.1.20.jar kotlin-compiler.jar
download $MVN/org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.jar kotlin-stdlib.jar
download $MVN/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar kotlinx-coroutines.jar
download $MVN/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar trove4j.jar
download $MVN/org/jetbrains/annotations/13.0/annotations-13.0.jar annotations.jar
download $MVN/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar jna.jar
download $MVN/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar jackson-core.jar
download $MVN/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar jackson-annotations.jar
download $MVN/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar jackson-databind.jar
download https://dl.google.com/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar r8.jar

DEPS="$WORK/kotlin-stdlib.jar:$WORK/jna.jar:$WORK/jackson-core.jar:$WORK/jackson-annotations.jar:$WORK/jackson-databind.jar"

# the harness, compiled against the library the way an app is
rm -rf "$WORK/harness-classes" "$WORK/harness.jar"
"$JAVA" -cp "$WORK/kotlin-compiler.jar:$WORK/kotlin-stdlib.jar:$WORK/kotlinx-coroutines.jar:$WORK/trove4j.jar:$WORK/annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp "$LIB_JAR:$DEPS" -d "$WORK/harness-classes" SmokeMain.kt
(cd "$WORK/harness-classes" && "$JAVA_HOME/bin/jar" cf "../harness.jar" .)

# the rules of the smoke: the consumer rules of the module, plus the entry point of the harness
cat ../consumer-rules.pro > "$WORK/smoke-rules.pro"
cat >> "$WORK/smoke-rules.pro" <<'RULES'
-keep class SmokeMain { public static void main(java.lang.String[]); }
-dontwarn org.jetbrains.annotations.**
RULES

# the minified jar: the harness, the library, and the dependencies, the way an app that turns on
# minification carries them
rm -f "$WORK/smoke.jar"
"$JAVA" -cp "$WORK/r8.jar" com.android.tools.r8.R8 --classfile --release \
  --output "$WORK/smoke.jar" --lib "$JAVA_HOME" --pg-conf "$WORK/smoke-rules.pro" \
  "$WORK/harness.jar" "$LIB_JAR" "$WORK/kotlin-stdlib.jar" "$WORK/jna.jar" \
  "$WORK/jackson-core.jar" "$WORK/jackson-annotations.jar" "$WORK/jackson-databind.jar"

"$JAVA" -cp "$WORK/smoke.jar" SmokeMain
