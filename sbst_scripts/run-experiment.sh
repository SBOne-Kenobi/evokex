#!/bin/bash

if [ $# -lt 5 ]
then
  echo "Usage: <tool-path> <benchmarks-path> <output-path> <runs-number> <list-of-time-budgets>"
  echo "example: ~/evokex ~/my-benchmark ~/output 3 30 60 120"
  exit 0;
fi

set -e

TOOL_HOME=$1
BENCH_PATH=$2
OUTPUT_PATH=$3
RUNS_NUMBER=$4
TOOL_NAME=$(basename "$TOOL_HOME")

for TIME_BUDGET in "${@:5}"
do
  RESULT_PATH="${OUTPUT_PATH}/${TOOL_NAME}-${TIME_BUDGET}"
  mkdir -p "$RESULT_PATH" > /dev/null
  cp -r "$TOOL_HOME"/* "$RESULT_PATH" > /dev/null

  ./setup-and-run-docker.sh "$RESULT_PATH" "$BENCH_PATH" "$RUNS_NUMBER" "$TIME_BUDGET"
done
