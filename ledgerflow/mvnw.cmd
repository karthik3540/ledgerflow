@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM
@REM Required ENV vars:
@REM JAVA_HOME - location of a JDK home dir
@REM
@REM Optional ENV vars
@REM MAVEN_BATCH_ECHO - set to 'on' to enable the echoing of the batch commands
@REM MAVEN_BATCH_PAUSE - set to 'on' to wait for key stroke before ending
@REM MAVEN_OPTS - parameters passed to the Java VM when running Maven
@REM     e.g. to debug Maven itself, use
@REM set MAVEN_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000
@REM MAVEN_SKIP_RC - flag to disable loading of mavenrc files
@REM ----------------------------------------------------------------------------

@IF "%MAVEN_BATCH_ECHO%" == "on"  echo %MAVEN_BATCH_ECHO%

@REM set %HOME% to equivalent of $HOME
if "%HOME%" == "" (set "HOME=%HOMEDRIVE%%HOMEPATH%")

@REM Execute a user defined script before this one
if not "%MAVEN_SKIP_RC%" == "" goto skipRc
@REM check for pre script, once with legacy .bat ending and once with .cmd ending
if exist "%USERPROFILE%\mavenrc_pre.bat" call "%USERPROFILE%\mavenrc_pre.bat" %*
if exist "%USERPROFILE%\mavenrc_pre.cmd" call "%USERPROFILE%\mavenrc_pre.cmd" %*
:skipRc

@setlocal

set ERROR_CODE=0

@REM To isolate internal variables from target environment, they are prefixed with MAVEN_
set MAVEN_PROJECTBASEDIR=%MAVEN_BASEDIR%
IF NOT "%MAVEN_PROJECTBASEDIR%"=="" goto endDetectBaseDir

set EXEC_DIR=%CD%
set WDIR=%EXEC_DIR%
:findBaseDir
IF EXIST "%WDIR%"\.mvn goto baseDirFound
cd ..
IF "%WDIR%"=="%CD%" goto baseDirNotFound
set WDIR=%CD%
goto findBaseDir

:baseDirFound
set MAVEN_PROJECTBASEDIR=%WDIR%
cd "%EXEC_DIR%"
goto endDetectBaseDir

:baseDirNotFound
set MAVEN_PROJECTBASEDIR=%EXEC_DIR%
cd "%EXEC_DIR%"

:endDetectBaseDir

IF NOT EXIST "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" (
    if not "%MVNW_REPOURL%" == "" (
        SET MVNW_REPO=%MVNW_REPOURL%
    ) else (
        SET MVNW_REPO=https://repo.maven.apache.org/maven2
    )
    if exist "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9-bin\*\apache-maven-3.9.9\bin\mvn.cmd" (
        for /f %%i in ('dir /b "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9-bin"') do set MAVEN_HOME="%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9-bin\%%i\apache-maven-3.9.9"
    )
)

IF EXIST "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" (
    IF NOT EXIST "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties" (
        echo Could not find .mvn\wrapper\maven-wrapper.properties
    )
)

@REM Find the project base dir, i.e. the directory that contains the folder ".mvn".
@REM Fallback to current directory if not found.

set MAVEN_JAVA_EXE="%JAVA_HOME%\bin\java.exe"
set MVN_EXE=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar

for /F "usebackq tokens=1,2 delims==" %%A in ("%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties") do (
    IF "%%A"=="distributionUrl" set MAVEN_DISTRIBUTION_URL=%%B
)

if "%MAVEN_HOME%" == "" goto downloadWrapper
set MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd
goto runMaven

:downloadWrapper
set WRAPPER_URL=%MVNW_REPO%/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
echo Downloading from: %WRAPPER_URL%
%MAVEN_JAVA_EXE% -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %WRAPPER_URL%

:runMaven
set MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd
if not exist "%MVN_CMD%" (
    %MAVEN_JAVA_EXE% -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
) else (
    call "%MVN_CMD%" %*
)

if ERRORLEVEL 1 goto error
goto end

:error
set ERROR_CODE=1

:end
@endlocal & set ERROR_CODE=%ERROR_CODE%

if not "%MAVEN_BATCH_PAUSE%"=="on" goto end2
echo Press any key to continue...
pause

:end2
if "%MAVEN_BATCH_ECHO%"=="on" echo Finished with %ERROR_CODE% error(s)

cmd /C exit /B %ERROR_CODE%
