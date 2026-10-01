@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
    echo Missing gradle-wrapper.jar: run gradle wrapper or copy it from ..\DashBuddy\gradle\wrapper\. 1>&2
    exit /b 1
)
set "JAVA_CMD=java.exe"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
