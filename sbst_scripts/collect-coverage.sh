#!/bin/bash

SCRIPT_DIR=$(pwd)

BASE_PATH=$1
RUNS_NUMBER=$2

EVO=$SCRIPT_DIR/master/target/evosuite-master-1.1.0.jar
JARS=$SCRIPT_DIR/sbst_scripts/jars
CONF=/var/benchmarks/conf/benchmarks.list
LOGS=$JARS/coverage_loggers

java -Xms16G -Xmx16G -jar "$JARS/coverage.jar" "$CONF" "$BASE_PATH" "$EVO" "$JARS/junit.jar" "$RUNS_NUMBER" "$LOGS"
