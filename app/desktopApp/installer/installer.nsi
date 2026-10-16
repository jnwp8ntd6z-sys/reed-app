Unicode true
!define APPNAME "Reed Client 3 (тест)"
Name "${APPNAME}"
OutFile "Reed-Client-3.0-test-setup.exe"
Icon "reed.ico"
UninstallIcon "reed.ico"
RequestExecutionLevel admin
InstallDir "$PROGRAMFILES64\Reed Client 3 test"
SetCompressor /SOLID lzma
!include "MUI2.nsh"
!define MUI_ICON "reed.ico"
!define MUI_UNICON "reed.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\Reed Client.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить Reed Client"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"
VIProductVersion "3.0.0.0"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "ProductName" "Reed Client"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "FileDescription" "Установка Reed Client 3 (тест)"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "FileVersion" "3.0.0"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "CompanyName" "Reed"
Section "Install"
  ; Если тестовая версия запущена — закрываем, иначе файлы заняты.
  nsExec::Exec 'taskkill /F /FI "IMAGENAME eq javaw.exe" /FI "WINDOWTITLE eq Reed*"'
  SetOutPath "$INSTDIR"
  File "Reed Client.exe"
  File "reed.ico"
  SetOutPath "$INSTDIR\app"
  File "app\reed.jar"
  SetOutPath "$INSTDIR\runtime"
  File /r "runtime\*.*"
  SetOutPath "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Reed"
  CreateShortcut "$SMPROGRAMS\Reed\${APPNAME}.lnk" "$INSTDIR\Reed Client.exe" "" "$INSTDIR\reed.ico"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\Reed Client.exe" "" "$INSTDIR\reed.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "DisplayName" "${APPNAME}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "DisplayVersion" "3.0.0"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "Publisher" "Reed"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "DisplayIcon" "$INSTDIR\reed.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "UninstallString" '"$INSTDIR\Uninstall.exe"'
SectionEnd
Section "Uninstall"
  Delete "$SMPROGRAMS\Reed\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test"
SectionEnd
