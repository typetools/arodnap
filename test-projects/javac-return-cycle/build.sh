#!/bin/sh
# Builds the project with plain javac, the way many small projects and scripts do.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 11 -d out $(find src -name '*.java')
