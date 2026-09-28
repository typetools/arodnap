#!/bin/sh
# An older project: sources in ISO-8859-1, compiled for Java 8.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 8 -encoding ISO-8859-1 -d out $(find src -name '*.java')
