#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Gradle start up script for UNIX.
#
# This is a trimmed wrapper launcher that only performs the bootstrap of the
# configured distribution via the local `gradle` command line. On machines with
# Gradle installed locally, run `gradle wrapper --gradle-version 8.7` from the
# project root to regenerate a full-featured wrapper (including the jar).
#

PrG="\$0"
appHome=
while [ -h "$PrG" ]; do
    ls=$(ls -l "$PrG")
    PrG=$(expr "$ls" : '.*-> \(.*\)$')
    case "$PrG" in
      /*) ;;
      *) PrG=$(dirname "$appHome")/"$PrG" ;;
    esac
done
appHome=$(cd "$(dirname "$PrG")" && pwd)

# Use the maximum available, or set MAX_FD without a limit whichever is lower
MAX_FD=maximum

warn () {
    echo "WARNING: $*"
}
die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support
cygwin=false; msys=false
case "$(uname)" in
    CYGWIN* )         cygwin=true ;;
    MINGW*|MSYS* )    msys=true ;;
esac

# Determine Java executable
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

CLASSPATH="$appHome/gradle/wrapper/gradle-wrapper.jar"
# No jar present in this placeholder wrapper; fall back to a local gradle install.
if [ -f "$CLASSPATH" ] && command -v "$JAVACMD" > /dev/null; then
    exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
fi

if command -v gradle > /dev/null; then
    cd "$appHome"
    exec gradle "$@"
fi

die "No wrapper jar and no local 'gradle' install found. Run 'gradle wrapper' on a dev machine to materialize gradle/wrapper/gradle-wrapper.jar."
