#!/bin/bash

die(){
  echo $1
  exit 1
}

export VERSION=$(head -n 1 project.clj | cut -d ' ' -f3 | cut -d '"' -f2 | cut -d '-' -f1)

echo "Building lein project with version $VERSION"
lein do test, jar || die "Build failed"
lein deploy clojars || die "Deploy failed"
