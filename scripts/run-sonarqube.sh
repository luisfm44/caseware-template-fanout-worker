#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

prove_gate_failure=false
case "${1:-}" in
    '') ;;
    --prove-gate-failure) prove_gate_failure=true ;;
    *) printf 'Usage: %s [--prove-gate-failure]\n' "$0" >&2; exit 2 ;;
esac
if (( $# > 1 )); then
    printf 'Usage: %s [--prove-gate-failure]\n' "$0" >&2
    exit 2
fi

readonly sonar_image=sonarqube:26.11.0.134175-community
readonly scanner_goal=org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar
readonly default_project_key=caseware-template-fanout-worker
project_key=$default_project_key

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

for command in curl jq; do
    command -v "$command" >/dev/null || fail "SonarQube analysis requires $command."
done

build_directory=${FANOUT_BUILD_DIRECTORY:-$PWD/target}
case "$build_directory" in
    /*) ;;
    *) build_directory="$PWD/$build_directory" ;;
esac
report_directory="$build_directory/sonar-quality"
coverage_report="$build_directory/site/jacoco/jacoco.xml"
[[ -s "$coverage_report" ]] || fail "Coverage report missing at $coverage_report. Run ./mvnw -Ppostgres-integration,coverage clean verify first."
mkdir -p -- "$report_directory"
rm -f -- "$report_directory"/{report-task.txt,compute-task.json,quality-gate.json,measures.json,issues.json,summary.md}

container_id=''
cleanup() {
    if [[ -n "$container_id" ]]; then
        docker rm --force --volumes "$container_id" >/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mask_in_ci() {
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        printf '::add-mask::%s\n' "$1"
    fi
}

admin_post() {
    local endpoint=$1
    shift
    curl --fail --silent --show-error --max-time 20 --user "admin:$admin_password" \
        --request POST "$SONAR_HOST_URL/$endpoint" "$@"
}

analysis_get() {
    local endpoint=$1
    shift
    curl --fail --silent --show-error --max-time 20 --header "Authorization: Bearer $SONAR_TOKEN" \
        --get "$SONAR_HOST_URL/$endpoint" "$@"
}

start_disposable_sonar() {
    local coverage_floor=$1
    local gate_name="Caseware coverage floor"
    local binding deadline status

    for command in docker openssl; do
        command -v "$command" >/dev/null || fail "Disposable SonarQube requires $command."
    done

    # This loopback-only instance uses SonarSource's Docker evaluation settings.
    container_id=$(docker run --detach --publish 127.0.0.1::9000 \
        --env SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true "$sonar_image")
    binding=$(docker port "$container_id" 9000/tcp)
    [[ "$binding" =~ ^127\.0\.0\.1:[0-9]+$ ]] || fail 'Docker did not publish the expected SonarQube loopback address.'
    SONAR_HOST_URL="http://$binding"

    deadline=$((SECONDS + 240))
    while (( SECONDS < deadline )); do
        status=$(curl --fail --silent --max-time 3 "$SONAR_HOST_URL/api/system/status" 2>/dev/null || true)
        if jq -e '.status == "UP"' >/dev/null <<< "$status"; then
            break
        fi
        sleep 3
    done
    if ! jq -e '.status == "UP"' >/dev/null <<< "${status:-}"; then
        docker logs --tail 80 "$container_id" >&2 || true
        fail 'The disposable SonarQube instance did not become ready.'
    fi

    local new_password="SqA9!$(openssl rand -hex 24)"
    mask_in_ci "$new_password"
    admin_password=admin
    admin_post api/users/change_password \
        --data-urlencode 'login=admin' --data-urlencode 'previousPassword=admin' \
        --data-urlencode "password=$new_password" >/dev/null
    admin_password=$new_password

    admin_post api/projects/create \
        --data-urlencode "project=$project_key" --data-urlencode 'name=Caseware Template Fan-out Worker' \
        --data-urlencode 'mainBranch=main' --data-urlencode 'visibility=private' >/dev/null
    admin_post api/qualitygates/create --data-urlencode "name=$gate_name" >/dev/null
    admin_post api/qualitygates/create_condition \
        --data-urlencode "gateName=$gate_name" --data-urlencode 'metric=coverage' \
        --data-urlencode 'op=LT' --data-urlencode "error=$coverage_floor" >/dev/null
    admin_post api/qualitygates/select \
        --data-urlencode "gateName=$gate_name" --data-urlencode "projectKey=$project_key" >/dev/null

    SONAR_TOKEN=$(admin_post api/user_tokens/generate \
        --data-urlencode 'name=caseware-analysis' --data-urlencode 'type=PROJECT_ANALYSIS_TOKEN' \
        --data-urlencode "projectKey=$project_key" | jq -er '.token | select(type == "string" and length > 0)')
    mask_in_ci "$SONAR_TOKEN"
}

mode='configured server'
admin_password=''
if [[ "$prove_gate_failure" == true ]]; then
    mode='isolated failure probe (100% coverage)'
    start_disposable_sonar 100
elif [[ -z "${SONAR_HOST_URL:-}" && -z "${SONAR_TOKEN:-}" ]]; then
    mode='disposable Community Build (50% coverage floor)'
    start_disposable_sonar 50
else
    [[ -n "${SONAR_HOST_URL:-}" && -n "${SONAR_TOKEN:-}" ]] || fail 'Set both SONAR_HOST_URL and SONAR_TOKEN, or leave both unset.'
    git_ref=${GITHUB_REF:-$(git symbolic-ref --quiet HEAD || true)}
    [[ "$git_ref" == refs/heads/main ]] || fail 'The configured SonarQube project is reserved for main. Unset SONAR_HOST_URL and SONAR_TOKEN to run an isolated analysis.'
    project_key=${SONAR_PROJECT_KEY:-$default_project_key}
    mask_in_ci "$SONAR_TOKEN"
fi
SONAR_HOST_URL=${SONAR_HOST_URL%/}
export SONAR_HOST_URL SONAR_TOKEN

scanner_exit=0
(
    # Community Build analyzes the checkout as main. Disable GitHub branch auto-detection.
    unset GITHUB_ACTION GITHUB_ACTIONS GITHUB_REF GITHUB_REF_NAME GITHUB_BASE_REF GITHUB_HEAD_REF
    ./mvnw --batch-mode --no-transfer-progress -Pcoverage \
        "-Dfanout.build.directory=$build_directory" \
        "-Dsonar.projectKey=$project_key" \
        "-Dsonar.scanner.metadataFilePath=$report_directory/report-task.txt" \
        -Dsonar.scanner.skipJreProvisioning=true \
        -Dsonar.qualitygate.wait=true -Dsonar.qualitygate.timeout=300 \
        "$scanner_goal"
) || scanner_exit=$?

collect_reports() {
    local task_id
    [[ -s "$report_directory/report-task.txt" ]] || return 1
    task_id=$(sed -n 's/^ceTaskId=//p' "$report_directory/report-task.txt")
    [[ -n "$task_id" ]] || return 1

    analysis_get api/ce/task --data-urlencode "id=$task_id" > "$report_directory/compute-task.json" || return 1
    analysis_id=$(jq -er '.task.analysisId | select(type == "string" and length > 0)' "$report_directory/compute-task.json") || return 1
    analysis_get api/qualitygates/project_status --data-urlencode "analysisId=$analysis_id" \
        > "$report_directory/quality-gate.json" || return 1
    gate_status=$(jq -er '.projectStatus.status' "$report_directory/quality-gate.json") || return 1

    analysis_get api/measures/component --data-urlencode "component=$project_key" \
        --data-urlencode 'metricKeys=coverage,line_coverage,branch_coverage,duplicated_lines_density,violations' \
        > "$report_directory/measures.json" || return 1
    coverage=$(jq -er '.component.measures[] | select(.metric == "coverage") | .value' "$report_directory/measures.json") || return 1
}

collect_issues() {
    if [[ -n "$admin_password" ]]; then
        curl --fail --silent --show-error --max-time 20 --user "admin:$admin_password" \
            --get "$SONAR_HOST_URL/api/issues/search" --data-urlencode "components=$project_key" \
            --data-urlencode 'ps=100'
    else
        analysis_get api/issues/search --data-urlencode "components=$project_key" --data-urlencode 'ps=100'
    fi | jq -e --arg project "$project_key" '{issues: [.issues[] | select(.project == $project)]}'
}

analysis_id='Unavailable'
gate_status='Unavailable'
coverage='Unavailable'
report_exit=0
if collect_reports; then
    if ! collect_issues > "$report_directory/issues.json"; then
        rm -f -- "$report_directory/issues.json"
        printf 'The server did not return issue details. See the Quality Gate and measures reports.\n' >&2
    fi
else
    printf 'SonarQube did not return a complete analysis report. See the scanner log.\n' >&2
    report_exit=1
fi

write_summary() {
    printf '## SonarQube Quality Gate\n\n'
    printf '| Result | Value |\n| --- | --- |\n'
    printf '| Mode | %s |\n| Analysis ID | %s |\n| Quality Gate | **%s** |\n' "$mode" "$analysis_id" "$gate_status"
    printf '| Coverage (%%) | %s |\n| Scanner exit code | %s |\n' "$coverage" "$scanner_exit"
    if [[ "$report_exit" == 0 ]]; then
        printf '\n| Condition | Status | Actual | Threshold |\n| --- | --- | --- | --- |\n'
        jq -r '.projectStatus.conditions[]? | "| \(.metricKey) | \(.status) | \(.actualValue // "-") | \(.comparator) \(.errorThreshold) |"' \
            "$report_directory/quality-gate.json"
    fi
}
if ! write_summary | tee "$report_directory/summary.md"; then
    report_exit=1
fi

if [[ "$prove_gate_failure" == true && "$gate_status" != ERROR ]]; then
    printf 'The failure probe did not produce a failed Quality Gate.\n' >&2
fi
if (( scanner_exit != 0 )); then
    exit "$scanner_exit"
fi
if (( report_exit != 0 )) || [[ "$gate_status" != OK || "$prove_gate_failure" == true ]]; then
    exit 1
fi
