#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mvn -B -ntp -f desktop-backend/pom.xml clean package
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_HOME
rm -rf build/java-runtime
mkdir -p build
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=2 --output build/java-runtime
rm -rf src-tauri/target/release/java-runtime src-tauri/target/release/backend src-tauri/target/debug/java-runtime src-tauri/target/debug/backend
