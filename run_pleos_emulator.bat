@echo off
rem Starts the Pleos Connect emulator through ASCII-only drive letters.
rem The emulator hangs at boot when SDK / AVD paths contain Korean characters.
rem S: -> %LOCALAPPDATA%\Android   T: -> %USERPROFILE%\.android  (removed on reboot)

if not exist S:\Sdk\emulator\emulator.exe subst S: "%LOCALAPPDATA%\Android"
if not exist T:\avd subst T: "%USERPROFILE%\.android"

if not exist C:\Android\tmp mkdir C:\Android\tmp
set ANDROID_SDK_ROOT=S:\Sdk
set ANDROID_HOME=S:\Sdk
set ANDROID_USER_HOME=T:\
set ANDROID_AVD_HOME=T:\avd
set ANDROID_EMULATOR_HOME=T:\
set TEMP=C:\Android\tmp
set TMP=C:\Android\tmp

for /d %%L in (T:\avd\PleosConnect.avd\*.lock) do rmdir /s /q "%%L"
start "" S:\Sdk\emulator\emulator.exe -avd PleosConnect -no-snapshot-load -dns-server 8.8.8.8,1.1.1.1
echo Pleos Connect emulator is starting. First boot can take a few minutes.
