#!/usr/bin/env bash
# $1 -> you are supposed to pass relative path to where the generated package is

# fail if any commands fails
set -e
# debug log
set -x

cd "$1"
pub get
dartanalyzer lib
