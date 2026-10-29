#!/usr/bin/env bash
# Verify every `external fun` declared in app/src/main/java has a matching
# Java_* symbol exported by the given libkinetic.so. A missing one would only
# surface at runtime as an UnsatisfiedLinkError.
#
# Usage: scripts/check-jni-symbols.sh path/to/libkinetic.so
set -euo pipefail

lib=${1:?usage: $0 libkinetic.so}
root=$(cd "$(dirname "$0")/.." && pwd)

# Symbol = Java_<package>_<innermost class/object seen so far>_<method>.
expected=$(find "$root/app/src/main/java" -name '*.kt' -print0 | xargs -0 awk '
    { sub(/\r$/, "") }
    FNR == 1 { cls = "" }
    /^package / { pkg = $2; gsub(/\./, "_", pkg) }
    /^(class|object) / { sub(/^(class|object) /, ""); match($0, /[A-Za-z0-9_]+/); cls = substr($0, RSTART, RLENGTH) }
    /external fun / {
        line = $0; sub(/.*external fun /, "", line); match(line, /[A-Za-z0-9_]+/)
        print "Java_" pkg "_" cls "_" substr(line, RSTART, RLENGTH)
    }' | sort -u)

actual=$(nm -D --defined-only "$lib" | awk '{print $3}' | sort -u)

missing=$(comm -23 <(echo "$expected") <(echo "$actual"))
if [ -n "$missing" ]; then
    echo "external funs without a JNI symbol in $lib:" >&2
    echo "$missing" >&2
    exit 1
fi
echo "OK: all $(echo "$expected" | wc -l) external funs have JNI symbols"
