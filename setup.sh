#!/bin/bash
cd "$( dirname "${BASH_SOURCE[0]}" )"
[ -d gradle-user-home ] || mkdir gradle-user-home
cd generator
./gradlew run "$@" || echo 'Running the generator failed'

