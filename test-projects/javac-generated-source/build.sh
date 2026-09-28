#!/bin/sh
# A build with a code generator: it writes gen/demo/Generated.java, then compiles it with src/.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out gen/demo
cp templates/Generated.java.txt gen/demo/Generated.java
javac --release 11 -d out $(find src gen -name '*.java')
