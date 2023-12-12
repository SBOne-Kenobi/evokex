#!/bin/bash

if [ $# -ne 3 ]
then
  echo "Usage <tool-name> <runs-number> <time-budget (seconds)>"
  echo "example: run-experiment.sh evokex 3 60"
  exit 0;
fi

TOOL_NAME=$1
RUNS_NUMBER=$2
TIME_BUDGET=$3

echo "$TOOL_NAME |>>>>> Running test generation $RUNS_NUMBER times with time-budget=$TIME_BUDGET"
contest_generate_tests.sh "$TOOL_NAME" "$RUNS_NUMBER" 1 "$TIME_BUDGET" > generation_log.txt 2> error_generation_log.txt

echo "$TOOL_NAME |>>>>> Running metrics collection"
#contest_compute_metrics.sh "results_${TOOL_NAME}_${TIME_BUDGET}" > metrics_log.txt 2> error_metrics_log.txt
./sbst_scripts/collect-coverage.sh "results_${TOOL_NAME}_${TIME_BUDGET}" "$RUNS_NUMBER" > metrics_log.txt 2> error_metrics_log.txt

echo "$TOOL_NAME |>>>>> Cleaning"
find . -name "instrumented" -type d -exec rm -r "{}" \; > /dev/null

echo "$TOOL_NAME |>>>>> Done"
