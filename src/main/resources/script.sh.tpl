#!/bin/bash
SCRIPT_NAME=${0}
JAR_NAME=${SCRIPT_NAME:0:`expr ${#SCRIPT_NAME} - 3`}.jar
jar xf $JAR_NAME lib/aspectjweaver-JWEAVER_VER.jar
java -javaagent:lib/aspectjweaver-JWEAVER_VER.jar EXTRA_ARGS -jar $JAR_NAME $@
