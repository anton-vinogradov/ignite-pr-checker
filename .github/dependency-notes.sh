#!/usr/bin/env bash
#
# The "Dependencies to update" section of the release notes, for the maintainer: what the checker is built and runs on
# that has lost free support or loses it within 90 days, by endoflife.date, and the Dependabot PRs still open. The
# versions come from build.gradle (Spring Boot), the Gradle wrapper, the JDK the release workflow sets up, and the
# libraries in the jar $1 (Spring Framework, Tomcat, Log4j). A check that cannot be made says "could not check", and
# the script still exits 0: a release never waits for endoflife.date or GitHub. Run it from the repository root. Needs
# curl, jq, unzip, and gh with GH_TOKEN. TODAY=YYYY-MM-DD sets the day the 90 days count from.
#
set -uo pipefail

jar="${1:-build/libs/ignite-pr-checker.jar}"
today="${TODAY:-$(date -u +%Y-%m-%d)}"
days=90
api=https://endoflife.date/api/v1/products
workflow=.github/workflows/release.yml
unreachable=

# The first group of the extended regex $2 on the first line of file $1 it matches.
version_in() { sed -nE "s#$2#\1#p" "$1" 2>/dev/null | head -n 1; }

# The version of the library in the jar whose file name starts with $1, as in BOOT-INF/lib/spring-core-6.2.19.jar.
jar_version() { unzip -Z1 "$jar" 2>/dev/null | sed -nE "s#^BOOT-INF/lib/$1-([0-9][^/]*)\.jar\$#\1#p" | head -n 1; }

# One line about product $1 (named $2 in the notes) at version $3, found in $4: "due", "ok" or "fail", a tab, the text.
# The release cycle is the longest release name endoflife.date lists that the version starts with, as 3.5 for 3.5.16.
check() {
    local product="$1" label="$2" version="$3" from="$4" json
    if [ -z "$version" ]; then
        printf 'fail\t%s: could not check, no version found in %s.\n' "$label" "$from"
        return
    fi
    # After one call fails the others are not made: each could wait out the timeout too.
    if [ -n "$unreachable" ] || ! json="$(curl -fsSL --max-time 20 --retry 2 "$api/$product")"; then
        unreachable=1
        printf 'fail\t%s %s: could not check, endoflife.date did not answer.\n' "$label" "$version"
        return
    fi
    printf '%s' "$json" | jq -er --arg v "$version" --arg label "$label" --arg from "$from" --arg today "$today" \
        --argjson days "$days" '
        def day: strptime("%Y-%m-%d") | mktime;
        ([.result.releases[] | select(.name as $n | $v == $n or ($v | startswith($n + ".")))]
            | max_by(.name | length)) as $r
        | if $r == null then "fail\t\($label) \($v): could not check, endoflife.date lists no release of it."
          elif $r.eolFrom == null and $r.isEol != true then "ok\t\($label) \($v)"
          elif $r.eolFrom == null then "due\t**\($label) \($r.name)** (\($v) in \($from)): free support has ended."
          elif $r.eolFrom <= $today then
              "due\t**\($label) \($r.name)** (\($v) in \($from)): free support ended on \($r.eolFrom)."
          elif ($r.eolFrom | day) - ($today | day) <= $days * 86400 then
              "due\t**\($label) \($r.name)** (\($v) in \($from)): free support ends on \($r.eolFrom), in "
              + "\((($r.eolFrom | day) - ($today | day)) / 86400 | floor) days."
          else "ok\t\($label) \($v)" end' 2>/dev/null \
        || printf 'fail\t%s %s: could not check, endoflife.date sent no list of releases.\n' "$label" "$version"
}

java="$(version_in "$workflow" "^ *java-version: *'?([0-9]+)'?.*")"
distribution="$(version_in "$workflow" "^ *distribution: *'?([a-z]+)'?.*")"

results="$(
    check spring-boot "Spring Boot" "$(version_in build.gradle ".*id 'org\.springframework\.boot' version '([^']+)'.*")" \
        build.gradle
    check gradle Gradle "$(version_in gradle/wrapper/gradle-wrapper.properties ".*/gradle-([0-9.]+)-(bin|all)\.zip.*")" \
        "the Gradle wrapper"
    if [ "$distribution" = temurin ]; then
        check eclipse-temurin "Eclipse Temurin" "$java" "the release workflow"
    else
        printf 'fail\tJava %s: could not check, %s.\n' "${java:-?}" \
            "this script looks up only Temurin, and the release workflow sets up ${distribution:-no JDK}"
    fi
    check spring-framework "Spring Framework" "$(jar_version spring-core)" "the jar"
    check tomcat "Apache Tomcat" "$(jar_version tomcat-embed-core)" "the jar"
    check log4j "Apache Log4j" "$(jar_version log4j-api)" "the jar"
)"

horizon="$(jq -nr --arg today "$today" --argjson days "$days" \
    '$today | strptime("%Y-%m-%d") | mktime + $days * 86400 | strftime("%Y-%m-%d")')"
# The lines of kind $1, as list items.
lines() { printf '%s\n' "$results" | awk -F '\t' -v kind="$1" '$1 == kind { print "- " $2 }'; }
supported="$(printf '%s\n' "$results" | awk -F '\t' '$1 == "ok" { s = s (s == "" ? "" : ", ") $2 } END { print s }')"

echo "## Dependencies to update"
echo
echo "End of free support, as [endoflife.date](https://endoflife.date) lists it on $today:"
echo
lines due
[ -z "$supported" ] || echo "- $supported: no end of free support before $horizon."
lines fail
echo

if prs="$(gh pr list --state open --author app/dependabot --limit 100 --json number,title \
    --jq '.[] | "- #\(.number) \(.title)"' 2>/dev/null)"; then
    if [ -n "$prs" ]; then
        echo "Open Dependabot PRs:"
        echo
        printf '%s\n' "$prs"
    else
        echo "No Dependabot PR is open."
    fi
else
    echo "Open Dependabot PRs: could not check, \`gh pr list\` failed."
fi
echo

exit 0
