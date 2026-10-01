#!/bin/sh
# Minimal wrapper launcher; bootstrap gradle-wrapper.jar as documented in README.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Missing gradle-wrapper.jar: run gradle wrapper or copy ../DashBuddy/gradle/wrapper/gradle-wrapper.jar." >&2
    exit 1
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD=java
fi
# JAVA_OPTS and GRADLE_OPTS intentionally split into JVM arguments.
exec "$JAVA_CMD" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
