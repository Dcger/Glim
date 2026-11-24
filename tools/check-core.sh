#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fact_check_dir="$(mktemp -d)"
trap 'rm -rf "$fact_check_dir"' EXIT
fact_source_dir=app/src/main/java/dev/discovery/wallpaper
java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options \
  -encoding UTF-8 -d "$fact_check_dir" \
  "$fact_source_dir/model/Discovery.java" "$fact_source_dir/sources/PdfLocator.java" tools/RotationCheck.java
java -cp "$fact_check_dir" dev.discovery.wallpaper.model.RotationCheck
