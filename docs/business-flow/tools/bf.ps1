$ErrorActionPreference = 'Stop'
$jarPath = Join-Path $PSScriptRoot 'target/business-flow-tools.jar'
if (-not (Test-Path $jarPath)) {
    throw 'Build first: mvn -f docs/business-flow/tools/pom.xml verify'
}
& java '-Dfile.encoding=UTF-8' '-jar' $jarPath @args
exit $LASTEXITCODE
