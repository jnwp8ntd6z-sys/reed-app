; «Reed Client.exe»: запускает приложение встроенной Java. Сразу с правами администратора —
; туннель (адаптер Reed) поднимается только с ними; служба Windows (этап 3) это уберёт.
Unicode true
!include "FileFunc.nsh"
Name "Reed Client"
OutFile "Reed Client.exe"
Icon "reed.ico"
RequestExecutionLevel admin
SilentInstall silent
VIProductVersion "3.0.0.0"
VIAddVersionKey "ProductName" "Reed Client"
VIAddVersionKey "FileDescription" "Reed Client"
VIAddVersionKey "FileVersion" "3.0.0"
VIAddVersionKey "ProductVersion" "3.0.0"
VIAddVersionKey "CompanyName" "Reed"
VIAddVersionKey "LegalCopyright" "© 2026 Reed"
Section
  ${GetParameters} $0
  SetOutPath "$EXEDIR"
  Exec '"$EXEDIR\runtime\bin\javaw.exe" -Djpackage.app-version=3.0.0 -Djpackage.app-path="$EXEDIR\Reed Client.exe" -Dfile.encoding=UTF-8 -jar "$EXEDIR\app\reed.jar" $0'
SectionEnd
