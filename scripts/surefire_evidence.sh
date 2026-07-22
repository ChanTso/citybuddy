#!/usr/bin/env bash

clear_surefire_reports() {
  local report_dir="$1"
  shift
  local class_name
  for class_name in "$@"; do
    rm -f "$report_dir/TEST-$class_name.xml"
  done
}

assert_surefire_classes_executed() {
  local report_dir="$1"
  shift
  local class_name report suite executed_tests
  for class_name in "$@"; do
    report="$report_dir/TEST-$class_name.xml"
    if [[ ! -f "$report" ]]; then
      echo "Missing fresh Surefire report for $class_name." >&2
      return 1
    fi
    suite="$(sed -n '/<testsuite /{p;q;}' "$report")"
    if [[ "$suite" =~ tests=\"([1-9][0-9]*)\" ]]; then
      executed_tests="${BASH_REMATCH[1]}"
    else
      echo "Surefire did not execute any required $class_name tests: $suite" >&2
      return 1
    fi
    if [[ ! "$suite" =~ skipped=\"0\" ]] \
      || [[ ! "$suite" =~ failures=\"0\" ]] \
      || [[ ! "$suite" =~ errors=\"0\" ]]; then
      echo "Surefire did not execute every required $class_name test successfully: $suite" >&2
      return 1
    fi
    echo "Verified Surefire executed $executed_tests required tests for $class_name with no skips."
  done
}
