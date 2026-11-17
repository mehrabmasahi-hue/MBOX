@echo off
setlocal
set VERSION=9.6.0
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (gradle %* & exit /b %ERRORLEVEL%)
set BASE=%USERPROFILE%\.gradle\mbox-bootstrap
set HOME_DIR=%BASE%\gradle-%VERSION%
if not exist "%HOME_DIR%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip'; $z='%BASE%\gradle-%VERSION%-bin.zip'; Invoke-WebRequest $u -OutFile $z; Expand-Archive -Force $z '%BASE%'"
)
call "%HOME_DIR%\bin\gradle.bat" %*
