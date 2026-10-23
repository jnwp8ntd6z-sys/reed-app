Unicode true
!define APPNAME "Reed Client"
!define VER "3.0.2"
Name "${APPNAME}"
OutFile "Reed-Client-${VER}-setup.exe"
Icon "reed.ico"
UninstallIcon "reed.ico"
RequestExecutionLevel admin
InstallDir "$PROGRAMFILES64\Reed Client"
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
VIProductVersion "${VER}.0"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "ProductName" "${APPNAME}"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "FileDescription" "Установка ${APPNAME}"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "FileVersion" "${VER}"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "ProductVersion" "${VER}"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "CompanyName" "Reed"
VIAddVersionKey /LANG=${LANG_RUSSIAN} "LegalCopyright" "© 2026 Reed"

; Закрыть работающий клиент (2.0.x — jpackage-лаунчер «Reed Client.exe», 3.0 — javaw) и движки.
!macro KillRunning
  nsExec::Exec 'taskkill /F /IM "Reed Client.exe"'
  nsExec::Exec 'taskkill /F /FI "IMAGENAME eq javaw.exe" /FI "WINDOWTITLE eq Reed*"'
  ; Клиент, свёрнутый в трей, окна не имеет — ищем javaw с reed.jar в командной строке.
  nsExec::Exec `powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | Where-Object { $$_.CommandLine -like '*reed.jar*' } | ForEach-Object { Stop-Process -Id $$_.ProcessId -Force }"`
  nsExec::Exec 'taskkill /F /IM sing-box-windows-amd64.exe'
  nsExec::Exec 'taskkill /F /IM tun2socks-windows-amd64.exe'
!macroend

Section "Install"
  SetRegView 64
  ; Обновление поверх: служба держит файлы java — сначала останавливаем и снимаем её.
  IfFileExists "$INSTDIR\ReedService.exe" 0 +3
    nsExec::Exec '"$INSTDIR\ReedService.exe" stop'
    nsExec::Exec '"$INSTDIR\ReedService.exe" uninstall'
  !insertmacro KillRunning

  ; Старая версия 2.0.x стояла MSI-пакетом (jpackage). Находим её по имени в списке
  ; «Установка и удаление программ» и удаляем тихо, чтобы не было двух «Reed Client».
  StrCpy $0 0
  StrCpy $5 0
  msiLoop:
    EnumRegKey $1 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall" $0
    StrCmp $1 "" msiDone
    StrCpy $4 $1 1
    StrCmp $4 "{" 0 msiNext
    ReadRegStr $2 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$1" "DisplayName"
    StrCmp $2 "Reed Client" 0 msiNext
    DetailPrint "Удаляю старую версию Reed Client ($1)"
    ExecWait 'msiexec.exe /x $1 /qn /norestart'
    IntOp $5 $5 + 1
    IntCmp $5 5 msiDone
    ReadRegStr $2 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$1" "DisplayName"
    StrCmp $2 "" 0 msiNext      ; ключ пропал — начинаем перечисление заново
    StrCpy $0 0
    Goto msiLoop
  msiNext:
    IntOp $0 $0 + 1
    Goto msiLoop
  msiDone:

  ; Тестовая сборка 3.0 (отдельная папка) — тоже убираем.
  ReadRegStr $3 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test" "UninstallString"
  StrCmp $3 "" noTest
    DetailPrint "Удаляю тестовую версию"
    ExecWait '$3 /S _?=$PROGRAMFILES64\Reed Client 3 test'
    RMDir /r "$PROGRAMFILES64\Reed Client 3 test"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient3Test"
    Delete "$DESKTOP\Reed Client 3 (тест).lnk"
    Delete "$SMPROGRAMS\Reed\Reed Client 3 (тест).lnk"
  noTest:

  SetOutPath "$INSTDIR"
  File "Reed Client.exe"
  File "reed.ico"
  SetOutPath "$INSTDIR\app"
  File "app\reed.jar"
  SetOutPath "$INSTDIR\runtime"
  File /r "runtime\*.*"
  SetOutPath "$INSTDIR\bin"
  File "bin\sing-box-windows-amd64.exe"
  SetOutPath "$INSTDIR"
  File /oname=ReedService.exe "WinSW-net461.exe"
  File "ReedService.xml"
  CreateDirectory "$INSTDIR\data"
  ; Служба: ставим и запускаем (автозапуск с Windows, перезапуск при сбое).
  nsExec::ExecToLog '"$INSTDIR\ReedService.exe" install'
  nsExec::ExecToLog '"$INSTDIR\ReedService.exe" start'
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Reed"
  CreateShortcut "$SMPROGRAMS\Reed\${APPNAME}.lnk" "$INSTDIR\Reed Client.exe" "" "$INSTDIR\reed.ico"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\Reed Client.exe" "" "$INSTDIR\reed.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "DisplayName" "${APPNAME}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "DisplayVersion" "${VER}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "Publisher" "Reed"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "DisplayIcon" "$INSTDIR\reed.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetRegView 64
  nsExec::Exec '"$INSTDIR\ReedService.exe" stop'
  nsExec::Exec '"$INSTDIR\ReedService.exe" uninstall'
  !insertmacro KillRunning
  Delete "$SMPROGRAMS\Reed\${APPNAME}.lnk"
  RMDir "$SMPROGRAMS\Reed"
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\ReedClient"
SectionEnd
