# Сборка Windows-клиента без GitHub Actions (сервер бота, /opt/winbuild)

- `build.sh` — Gradle: тесты десктопа + `packageUberJarForCurrentOS` (копия `app/` без Android/iOS,
  sharedUI только jvm, compose.desktop.windows_x64; gradle.properties ужат под 1 ГБ). `guard.sh` — убивает
  Gradle, если на сервере остаётся < 200 МБ памяти. `render.sh` — рендер раскладки в PNG (DesktopLayoutRenderTest).
- `launcher.nsi` → «Reed Client.exe» (javaw -jar app/reed.jar, запрашивает admin).
- `release.nsi` → `Reed-Client-<ver>-setup.exe`: закрывает клиент, тихо удаляет MSI 2.0.x и тестовую 3.0,
  ставит в Program Files\Reed Client, ярлыки, запись в «Установка и удаление программ».
- `installer.nsi` → тестовая сборка в отдельную папку «Reed Client 3 test».
- Выкладка: /var/www/reed-download/Reed-Client-<ver>-setup.exe + копия в Reed-Client-setup.exe.
