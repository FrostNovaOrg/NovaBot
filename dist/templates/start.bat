@echo off
chcp 65001 > nul
cd /d "%~dp0"

set JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=192m
set JAVA_OPTS=%JAVA_OPTS% -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true
set JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8

java %JAVA_OPTS% -Dloader.path=lib,plugins-lib -jar StarBotCore.jar
pause
