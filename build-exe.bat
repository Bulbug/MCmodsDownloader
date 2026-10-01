@echo off
setlocal

echo === Building jars ===
if exist gradlew.bat (
    call gradlew.bat clean prepareJpackage || goto :error
) else (
    call gradle clean prepareJpackage || goto :error
)

if exist dist rmdir /s /q dist

echo === Creating portable app (no WiX needed) ===
jpackage ^
  --type app-image ^
  --name MinecraftManager ^
  --app-version 0.1.0 ^
  --vendor "Kazu" ^
  --input build\jpackage-input ^
  --main-jar mc-manager.jar ^
  --main-class app.Launcher ^
  --dest dist ^
  --add-modules java.base,java.desktop,java.logging,java.xml,java.naming,java.sql,java.net.http,jdk.unsupported,jdk.jsobject,jdk.crypto.ec ^
  --java-options "-Xmx512m" || goto :error

echo.
echo Done: dist\MinecraftManager\MinecraftManager.exe
pause
exit /b 0

:error
echo Build failed.
pause
exit /b 1
