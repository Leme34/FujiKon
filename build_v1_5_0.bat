@echo off
set JAVA_HOME=D:\AI_WorkSpace\.tools\jdk\jdk17.0.20_12
set PATH=%JAVA_HOME%\bin;%PATH%
echo Building v1.5.0...
gradlew.bat :app:assembleDebug > v1_5_0_build.log 2>&1
echo Exit: %ERRORLEVEL%
