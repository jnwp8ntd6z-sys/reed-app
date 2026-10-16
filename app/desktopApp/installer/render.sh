#!/bin/bash
cd /opt/winbuild/src
export JAVA_HOME=/opt/winbuild/jdk21 GRADLE_USER_HOME=/opt/winbuild/gradle-home REED_RENDER_OUT=/opt/winbuild/shots
rm -rf /opt/winbuild/shots
nice -n 19 ionice -c3 ./gradlew --no-daemon :sharedUI:jvmTest --tests 'org.olcbox.app.ui.reed2.DesktopLayoutRenderTest' --console=plain
echo "EXIT $?"
