#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cmake_bin="${CMAKE_BIN:-/opt/android-sdk/cmake/3.22.1/bin/cmake}"
if [[ ! -x "$cmake_bin" ]]; then cmake_bin="$(command -v cmake)"; fi
"$cmake_bin" -S "$repo_root/container_runtime/transport" -B "$repo_root/container_runtime/build/transport-tests"
"$cmake_bin" --build "$repo_root/container_runtime/build/transport-tests" --parallel 2
"${cmake_bin%/cmake}/ctest" --test-dir "$repo_root/container_runtime/build/transport-tests" --output-on-failure
