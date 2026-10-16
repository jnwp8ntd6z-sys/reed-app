#!/bin/bash
cd /opt/winbuild/src
export JAVA_HOME=/opt/winbuild/jdk21 GRADLE_USER_HOME=/opt/winbuild/gradle-home
nice -n 19 ionice -c3 ./gradlew --no-daemon -Polcbox.version=3.0.1 :sharedUI:jvmTest --tests 'org.olcbox.app.vpn.desktop.*' :desktopApp:packageUberJarForCurrentOS -x verifyDesktopNativeResources --console=plain
echo "EXIT $?"
