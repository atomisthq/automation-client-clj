#!/bin/bash

export VERSION=$(head -n 1 project.clj | cut -d ' ' -f3 | cut -d '"' -f2 | cut -d '-' -f1)-$(date -u '+%Y%m%d%H%M%S')

echo "Building lein project with version $VERSION"
lein do test, jar
lein deploy clojars
