#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
repo="${M2_REPO:-$HOME/.m2/repository}"
artifacts=(
  com/fasterxml/jackson/core/jackson-databind/2.13.5/jackson-databind-2.13.5.jar
  com/fasterxml/jackson/core/jackson-core/2.13.5/jackson-core-2.13.5.jar
  com/fasterxml/jackson/core/jackson-annotations/2.13.5/jackson-annotations-2.13.5.jar
  org/apache/httpcomponents/httpclient/4.5.13/httpclient-4.5.13.jar
  org/apache/httpcomponents/httpcore/4.4.13/httpcore-4.4.13.jar
  commons-logging/commons-logging/1.2/commons-logging-1.2.jar
  commons-codec/commons-codec/1.11/commons-codec-1.11.jar
  com/h2database/h2/2.3.232/h2-2.3.232.jar
  junit/junit/4.13.2/junit-4.13.2.jar
  org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
)
classpath=""
for artifact in "${artifacts[@]}"; do
  test -f "$repo/$artifact" || { echo "Missing cached dependency: $artifact" >&2; exit 1; }
  classpath="${classpath:+$classpath:}$repo/$artifact"
done
mkdir -p target/local-classes target/local-test-classes
find src/main/java -name '*.java' | sort > target/local-sources.txt
javac --release 11 -encoding UTF-8 -cp "$classpath" -d target/local-classes @target/local-sources.txt
if [[ "${1:-test}" == "compile" ]]; then exit 0; fi
if [[ "${1:-test}" == "run" ]]; then
  exec java -cp "target/local-classes:$classpath" notification.NotificationApplication
fi
find src/test/java -name '*.java' | sort > target/local-test-sources.txt
javac --release 11 -encoding UTF-8 -cp "target/local-classes:$classpath" -d target/local-test-classes @target/local-test-sources.txt
java -cp "target/local-test-classes:target/local-classes:$classpath" org.junit.runner.JUnitCore notification.NotificationServiceTest