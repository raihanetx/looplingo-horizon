#!/bin/sh

PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

DEFAULT_JVM_OPTS=""

MAX_FD="maximum"

warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }

cygwin=false
msys=false
mingw=false
case "`uname`" in
  CYGWIN* ) cygwin=true ;;
  MINGW* ) mingw=true ;;
  MSYS* ) msys=true ;;
esac

if [ -z "$JAVA_HOME" ] ; then
    if $cygwin || $mingw || $msys ; then
        java_home_reg=`reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit\17" /v JavaHome 2>/dev/null | grep JavaHome | awk '{print $3}'`
        if [ -n "$java_home_reg" ] ; then JAVA_HOME="$java_home_reg"
        else
            java_home_reg=`reg query "HKLM\SOFTWARE\WOW6432Node\JavaSoft\Java Development Kit\17" /v JavaHome 2>/dev/null | grep JavaHome | awk '{print $3}'`
            if [ -n "$java_home_reg" ] ; then JAVA_HOME="$java_home_reg"; fi
        fi
    fi
fi

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then JAVACMD="$JAVA_HOME/jre/sh/java"
    else JAVACMD="$JAVA_HOME/bin/java"; fi
    if [ ! -x "$JAVACMD" ] ; then die "ERROR: JAVA_HOME is invalid: $JAVA_HOME"; fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME not set and no java in PATH"
fi

if [ "$cygwin" = "false" -a "$mingw" = "false" -a "$msys" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then MAX_FD="$MAX_FD_LIMIT"; fi
        ulimit -n $MAX_FD || warn "Could not set max FD limit: $MAX_FD"
    else warn "Could not query max FD limit: $MAX_FD_LIMIT"; fi
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
JVM_OPTS="$JAVA_OPTS $GRADLE_OPTS"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
