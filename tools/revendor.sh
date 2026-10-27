#!/bin/sh
# tools/revendor.sh <ref>: re-vendor the wire format from a fixed revision of
# mas-bandwidth/serialize, a released tag or a full commit sha (never a branch).
# STANDARD.md, conformance/ and conformance-controls/ are verbatim copies, and CI
# diffs them against the pinned revision, so this copies all three across and
# then shows the diff for review. After it runs, set SERIALIZE_REF in
# .github/workflows/ci.yml to the same ref, and port whatever the diff implies in
# the same pull request.
set -eu
ref="${1:?usage: tools/revendor.sh <ref>, a tag such as v2.0.0 or a full commit sha}"
cd "$(dirname "$0")/.."
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
git init --quiet "$scratch/serialize"
git -C "$scratch/serialize" fetch --quiet --depth 1 https://github.com/mas-bandwidth/serialize.git "$ref"
git -C "$scratch/serialize" -c advice.detachedHead=false checkout --quiet FETCH_HEAD
cp "$scratch/serialize/STANDARD.md" STANDARD.md
rm -rf conformance conformance-controls
cp -R "$scratch/serialize/conformance" conformance
cp -R "$scratch/serialize/conformance-controls" conformance-controls
echo "re-vendored from mas-bandwidth/serialize $ref ($(git -C "$scratch/serialize" rev-parse HEAD))"
git status --short -- STANDARD.md conformance conformance-controls
