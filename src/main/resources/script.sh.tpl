#!/bin/bash
SCRIPT_NAME=${0}
JAR_NAME=${SCRIPT_NAME:0:`expr ${#SCRIPT_NAME} - 3`}.jar
java EXTRA_ARGS -jar $JAR_NAME $@
