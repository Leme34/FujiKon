@echo off
setlocal EnableExtensions

REM ============================================================
REM  FujiKon - Clean rebuild APK (repeatable, version-agnostic)
REM
REM  Reads versionName / versionCode from app\build.gradle.kts,
REM  so bumping the version does NOT require editing this script.
REM
REM  Usage:  build_v1_5_0.bat
REM          set "JDK_HOME=D:\path\to\jdk-17"  (optional override)
REM ============================================================

cd /d "%~dp0"

REM ---------- 1. Locate JDK 17 (Gradle 8.7.3 needs Java 11+) ----------
if not defined JDK_HOME set "JDK_HOME=D:\AI_WorkSpace\.tools\jdk\jdk17.0.20_12"

if not exist "%JDK_HOME%\bin\java.exe" (
    echo [ERROR] JDK 17 not found at: %JDK_HOME%
    echo         Override before running:
    echo             set "JDK_HOME=D:\path\to\jdk-17"
    exit /b 1
)

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM The shared Gradle home on this machine lives under the Android SDK and is
REM not writable, so a new daemon cannot even create its log
REM ("Unable to create daemon log file"). Keep everything project-local instead;
REM .gradle-home is ignored by git and already holds the downloaded caches.
set "GRADLE_USER_HOME=%~dp0.gradle-home"

REM ---------- 2. Read app version from Gradle ----------
set "GRADLE_FILE=app\build.gradle.kts"
set "APP_VERSION="
set "VERSION_CODE="

for /f "tokens=2 delims==" %%v in ('findstr /r /c:"^[ ]*versionName[ ]*=" "%GRADLE_FILE%"') do set "RAW_VER=%%v"
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"^[ ]*versionCode[ ]*=" "%GRADLE_FILE%"') do set "RAW_CODE=%%v"

if defined RAW_VER (
    set "APP_VERSION=%RAW_VER: =%"
) else (
    echo [WARN] Could not parse versionName from %GRADLE_FILE%
    set "APP_VERSION=unknown"
)
REM Quote stripping must run outside the block above (no early expansion).
set APP_VERSION=%APP_VERSION:"=%

if defined RAW_CODE set "VERSION_CODE=%RAW_CODE: =%"
if not defined VERSION_CODE set "VERSION_CODE=?"

set "APK_DIR=app\build\outputs\apk\debug"
set "APK=%APK_DIR%\FujiKon-%APP_VERSION%.apk"

echo ============================================================
echo   FujiKon - Clean and Rebuild APK
echo   Version : %APP_VERSION%  (versionCode %VERSION_CODE%)
echo   JDK     : %JAVA_HOME%
echo   Started : %DATE% %TIME%
echo ============================================================
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "version"

REM ---------- 3. Clean ----------
REM A leftover Gradle/Kotlin daemon keeps holding the build directory and
REM several 4 GB heaps alive; clean then waits on a file lock and the Kotlin
REM compiler can run out of native memory (see hs_err_pid*.log). Stop the
REM daemons first and build in a single short-lived JVM with limited memory.
echo.
echo [0/2] Stopping stale Gradle daemons...
call gradlew.bat -Dorg.gradle.java.home="%JAVA_HOME%" --stop >nul 2>&1
REM A daemon left over from an interrupted build keeps holding the wrapper and
REM dependency files, so the next clean either waits forever or fails with
REM AccessDenied. Kill the leftovers (Gradle/Kotlin only, never the IDE).
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'GradleDaemon|KotlinCompileDaemon' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1
set "GRADLE_FLAGS=--no-daemon --no-parallel --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx2g"

echo.
echo [1/2] Cleaning build cache...
call gradlew.bat -Dorg.gradle.java.home="%JAVA_HOME%" %GRADLE_FLAGS% clean
if errorlevel 1 (
    echo [ERROR] Clean step failed.
    exit /b 1
)

REM ---------- 4. Build ----------
echo.
echo [2/2] Building debug APK...
call gradlew.bat -Dorg.gradle.java.home="%JAVA_HOME%" %GRADLE_FLAGS% :app:assembleDebug
if errorlevel 1 (
    echo [ERROR] Build step failed.
    exit /b 1
)

REM ---------- 5. Resolve APK (name derived from version, with fallback) ----------
if not exist "%APK%" (
    echo.
    echo [WARN] Expected APK not found: %APK%
    echo        Searching for the newest APK in %APK_DIR% ...
    for /f "delims=" %%f in ('dir /b /o-d "%APK_DIR%\*.apk" 2^>nul') do (
        set "APK=%APK_DIR%\%%f"
        goto :apk_found
    )
)
:apk_found

echo.
echo ============================================================
echo   Build finished.
echo   Ended : %DATE% %TIME%
echo ============================================================
if exist "%APK%" (
    echo APK: %CD%\%APK%
    dir "%APK%" /-C
) else (
    echo [ERROR] No APK produced.
    exit /b 1
)

endlocal
