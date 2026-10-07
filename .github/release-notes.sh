#!/usr/bin/env bash
#
# Release notes for tag $1, for whoever uses the checker and whoever presses Update: the "What changes for users"
# section of each PR merged since the previous tag, and a warning when TestVerdict.RULES changed, since then every
# cached verdict is computed again. The release workflow appends GitHub's own list of the PRs. Needs the history
# and the tags (fetch-depth: 0), and gh with GH_TOKEN.
#
set -euo pipefail

tag="$1"
prev="$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true)"
RULES_FILE=src/main/java/com/github/igniteprchecker/analysis/model/TestVerdict.java

rules() { { git show "$1:$RULES_FILE" 2>/dev/null || true; } | sed -n 's/.*int RULES = \([0-9]*\);.*/\1/p'; }

# The section of a PR description under "## What changes for users", up to the next "## " heading, without the
# template's hint; empty when it says nothing changes.
users_section() {
    awk '/^## What changes for users/ { on = 1; next } /^## / { on = 0 } on' | sed -e '/^<!--.*-->$/d' -e '/./,$!d' \
        | grep -viE '^[[:space:]]*(nothing|none)\.?[[:space:]]*$' || true
}

echo "## What changes for users"
echo

if [ -n "$prev" ]; then
    before="$(rules "$prev")"
    after="$(rules "$tag")"
    if [ -n "$before" ] && [ -n "$after" ] && [ "$before" != "$after" ]; then
        echo "> **Verdict rules changed** (RULES $before → $after): after the update every PR's verdict is computed"
        echo "> again, so the first page loads are slower, and blocker counts may change."
        echo
        if [ "${prev%.*}" = "${tag%.*}" ]; then
            echo "::warning::$tag changes the verdict rules in a patch release; release rule changes as a minor version" >&2
        fi
    fi
fi

described=0
while IFS= read -r subject; do
    pr="$(printf '%s\n' "$subject" | sed -n -e 's/.*(#\([0-9][0-9]*\))$/\1/p' -e 's/^Merge pull request #\([0-9][0-9]*\) .*/\1/p')"
    [ -n "$pr" ] || continue
    section="$(gh pr view "$pr" --json body --jq .body 2>/dev/null | tr -d '\r' | users_section || true)"
    [ -n "$section" ] || continue
    printf '### %s\n\n%s\n\n' "$(printf '%s\n' "$subject" | sed 's/ (#[0-9][0-9]*)$//')" "$section"
    described=$((described + 1))
done < <(git log --reverse --format=%s "${prev:+$prev..}$tag")

if [ "$described" = 0 ]; then
    echo "_None of the PRs below says what changes for users._"
    echo
fi
