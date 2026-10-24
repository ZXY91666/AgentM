@rem
@rem Copyright (c) 2015 2021 21 original authors.
@rem
@rem Gradle start up script for Windows.
@rem
@rem This is a trimmed wrapper launcher. On a dev machine run `gradle wrapper`
@rem to regenerate a full-featured wrapper including the jar.
@rem

setlocal
set AppHome=%~dp0
if "x%JAVA_HOME%" == "x" goto :noJava
if "x%CLASSPATH%" == "x" set CLASSPATH=%AppHome%gradle\wrapper\gradle-wrapper.jar

if exist "%AppHome%gradle\wrapper\gradle-wrapper.jar" (
    "%JAVA_HOME%\bin\java.exe" -classpath "%AppHome%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
    goto :end
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
    pushd %AppHome%
    gradle %*
    popd
    goto :end
)

echo No wrapper jar and no local gradle install found. 1>&2
exit /b 1

:
goto :eof
:end
exit /b 0
:noJava
echo JAVA_HOME is not set and java is not on PATH. 1>&2
exit /b 1
