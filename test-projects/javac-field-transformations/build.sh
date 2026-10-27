#!/bin/sh
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 11 -d out $(find src -name "*.java")
