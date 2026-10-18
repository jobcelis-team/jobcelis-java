#!/usr/bin/env bash
# Checks a Maven Central bundle the way Central validates it, so a broken
# release fails on the pull request instead of at publish time.
#   check-central-bundle.sh <dir with the com/... tree> <groupId> <artifactId> <version> [--checksums]
set -euo pipefail

ROOT=$1; GROUP=$2; ARTIFACT=$3; VERSION=$4; CHECKSUMS=${5:-}
BASE="${GROUP//.//}/$ARTIFACT/$VERSION"
PREFIX="$BASE/$ARTIFACT-$VERSION"
FAIL=0
fail() { echo "::error::$1"; FAIL=1; }

cd "$ROOT"

# Central rejects anything outside the version folder ("does NOT have a .pom file")
while IFS= read -r f; do
  case "$f" in
    "$BASE"/*) ;;
    *) fail "Unexpected file outside $BASE: $f" ;;
  esac
done < <(find . -type f | sed 's#^\./##')

for f in "$PREFIX.pom" "$PREFIX.jar" "$PREFIX-sources.jar" "$PREFIX-javadoc.jar"; do
  if [ ! -f "$f" ]; then fail "Missing $f"; continue; fi
  if [ ! -f "$f.asc" ]; then
    fail "Missing signature $f.asc"
  elif ! gpg --batch --verify "$f.asc" "$f" 2>/dev/null; then
    fail "Bad signature $f.asc"
  fi
  if [ -n "$CHECKSUMS" ]; then
    for c in md5 sha1; do [ -f "$f.$c" ] || fail "Missing checksum $f.$c"; done
  fi
done

if [ -f "$PREFIX.pom" ]; then
  for tag in name description url licenses developers scm; do
    grep -q "<$tag>" "$PREFIX.pom" || fail "The pom has no <$tag> (required by Maven Central)"
  done
fi

[ "$FAIL" = 0 ] && echo "Bundle for $GROUP:$ARTIFACT:$VERSION is valid"
exit "$FAIL"
