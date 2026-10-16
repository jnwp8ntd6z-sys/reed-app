#!/bin/bash
# сторож: если у системы < 200 МБ доступной памяти — убить сборку
while pgrep -f "GradleWrapperMain|GradleMain|gradle-launcher" >/dev/null; do
  a=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
  if [ "$a" -lt 200 ]; then echo "$(date) MemAvailable=${a}MB — kill gradle"; pkill -9 -f "GradleWrapperMain|GradleDaemon|GradleMain|KotlinCompileDaemon"; fi
  sleep 3
done
