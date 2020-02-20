#!/usr/bin/env bash
set -e

VERSION_PATH=src/yajsw/src/main/java/org/rzo/yajsw/YajswVersion.java
echo "Detecting version from ${VERSION_PATH}"
VERSION=$(fgrep YAJSW_VERSION ${VERSION_PATH} | cut -d '=' -f 2 | cut -d '-' -f 3 | cut -d '"' -f 1)
echo "Got ${VERSION}"
read -p "Confirm deployment of version ${VERSION} or fix ${VERSION_PATH}? [y/N] " -n 1 -r
echo    # (optional) move to a new line
if [[ ! $REPLY =~ ^[Yy]$ ]]
then
    [[ "$0" = "$BASH_SOURCE" ]] && exit 1 || return 1 # handle exits from shell or function but don't exit interactive shell
fi

echo "!! Gradle 5.x required"

pushd build/gradle
chmod +x gradlew.sh
gradlew.sh
popd
cp -p build/gradle/wrapper/build/libs/wrapper.jar .
cp -p build/gradle/wrapper-app/build/libs/wrapperApp.jar .


[ -f yajsw-stable-${VERSION}.zip ] && rm -f yajsw-stable-${VERSION}.zip
[ -d /tmp/yajsw-build/yajsw-stable-${VERSION} ] && rm -rf /tmp/yajsw-build/yajsw-stable-${VERSION}
mkdir -p /tmp/yajsw-build/yajsw-stable-${VERSION}
cp -rp . /tmp/yajsw-build/yajsw-stable-${VERSION}/
pushd /tmp/yajsw-build/yajsw-stable-${VERSION}/..
zip --exclude *build* --exclude *.idea* --exclude *.git* --exclude \*.iml -r /tmp/yajsw-build/yajsw-stable-${VERSION}.zip yajsw-stable-${VERSION}
popd
cp -p /tmp/yajsw-build/yajsw-stable-${VERSION}.zip .


echo "Jar generated".

cat <<EOF > /tmp/yajsw-build/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion>
<groupId>yajsw</groupId>
<artifactId>yajsw-stable</artifactId>
<version>${VERSION}</version>
<description>NetCeler's fork of YAJSW</description>
<licenses>
	<license>
		<name>Apache 2.0</name>
	</license>
</licenses>
</project>

EOF

echo "Validating pom"
mvn -f /tmp/yajsw-build/pom.xml validate

echo "Deploying to maven repository"
mvn -f /tmp/yajsw-build/pom.xml deploy:deploy-file -DpomFile=/tmp/yajsw-build/pom.xml \
  -Dfile=yajsw-stable-${VERSION}.zip  \
  -Dpackaging=zip \
  -DrepositoryId=thirdparty \
  -Durl=https://maven.netceler.com/service/local/repositories/thirdparty

