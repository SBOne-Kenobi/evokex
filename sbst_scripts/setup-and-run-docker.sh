#!/bin/bash

if [ $# -ne 4 ]
then
  echo "Usage: <tool-path> <benchmarks-path> <runs-number> <time-budget>"
  echo "example: ~/evokex ~/my-benchmarks 3 60"
  exit 0;
fi

TOOL_HOME=$1
BENCH_PATH=$2
RUNS_NUMBER=$3
TIME_BUDGET=$4

TOOL_NAME=$(basename "$TOOL_HOME")
DOCKER_TOOL_HOME=/home/$TOOL_NAME

docker run --rm -d \
  -v "$TOOL_HOME":"$DOCKER_TOOL_HOME" \
  -v "$BENCH_PATH":/var/benchmarks \
  --name="$TOOL_NAME" \
  -it junitcontest/infrastructure:latest > /dev/null

docker exec -w "$DOCKER_TOOL_HOME" "$TOOL_NAME" ./sbst_scripts/run-and-collect.sh "$TOOL_NAME" "$RUNS_NUMBER" "$TIME_BUDGET"

docker stop "$TOOL_NAME" > /dev/null
