#!/usr/bin/env sh
set -eu
java -version
mvn -B clean verify
echo "Built: target/GawharaEvents-1.0.1.jar"
