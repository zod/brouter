#!/usr/bin/env bash

set -eux

UPSTREAM_URL="https://github.com/cgeo/cgeo.git"
UPSTREAM_REPO="/home/zod/src/upstream/cgeo"
SOURCE_DIR="$UPSTREAM_REPO/main/src/cgeo/geocaching/brouter"

cp -r $SOURCE_DIR/codec brouter-codec/src/main/java/btools
cp -r $SOURCE_DIR/core/* brouter-core/src/main/java/btools/router
cp -r $SOURCE_DIR/expressions brouter-expressions/src/main/java/btools
cp -r $SOURCE_DIR/mapaccess brouter-mapaccess/src/main/java/btools
cp -r $SOURCE_DIR/util brouter-util/src/main/java/btools

BROUTER_UTIL="brouter-util/src/main/java/btools/util"
#mv $BROUTER_UTIL/CheapRulerHelper.java $BROUTER_UTIL/CheapRuler.java
#mv $BROUTER_UTIL/Crc32Utils.java $BROUTER_UTIL/Crc32.java
#mv $BROUTER_UTIL/FastMathUtils.java $BROUTER_UTIL/FastMath.java
rm $BROUTER_UTIL/DefaultFilesUtils.java

find . -name "*.java" -exec sed -i s/cgeo.geocaching.brouter/btools/ '{}' \;

# /opt/android-studio/bin/format.sh -m '*.java' -r .
