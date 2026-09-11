#!/usr/bin/env sh
set -eu
tool_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$tool_dir/target/business-flow-tools.jar" ]; then
  echo 'Build first: mvn -f docs/super-business-flow-tookit/tools/pom.xml verify' >&2
  exit 1
fi
exec java -Dfile.encoding=UTF-8 -jar "$tool_dir/target/business-flow-tools.jar" "$@"
