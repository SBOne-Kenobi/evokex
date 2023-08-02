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
RUN=run-and-collect.sh

docker run --rm -d \
  -v "$TOOL_HOME":/home/"$TOOL_NAME" \
  -v "$BENCH_PATH":/var/benchmarks \
  --name="$TOOL_NAME" \
  -it junitcontest/infrastructure:latest

docker cp "$RUN" "$TOOL_NAME":/usr/local/bin/

docker exec -w /home/"$TOOL_NAME" "$TOOL_NAME" "$RUN" "$TOOL_NAME" "$RUNS_NUMBER" "$TIME_BUDGET"

docker stop "$TOOL_NAME"
