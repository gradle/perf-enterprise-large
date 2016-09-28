#!/bin/bash
cd "$( dirname "${BASH_SOURCE[0]}" )"
[ -d ../../mavenRepo ] || mkdir ../../mavenRepo
MAVEN_REPO_DIR=$( cd ../../mavenRepo && pwd )
mvn "-Dmaven.repo.local=$MAVEN_REPO_DIR" clean install
