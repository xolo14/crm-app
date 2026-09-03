@ECHO OFF
REM Wrapper for Gradle — Android Studio will also use this after first sync.
SETLOCAL
SET APP_HOME=%~dp0
SET CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)

IF NOT EXIST "%CLASSPATH%" (
  ECHO Missing gradle\wrapper\gradle-wrapper.jar
  EXIT /B 1
)

"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
EXIT /B %ERRORLEVEL%
