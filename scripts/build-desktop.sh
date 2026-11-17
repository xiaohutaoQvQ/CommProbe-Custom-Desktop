#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/prepare-backend.sh
npx tauri build --bundles app
