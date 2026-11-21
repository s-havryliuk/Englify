@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven not found in PATH.
    echo Open Englify in IntelliJ IDEA and run com.englify.Launcher.
    echo Or open Maven tool window and choose javafx:run.
    pause
    exit /b 1
)
call mvn javafx:run
if errorlevel 1 (
    echo.
    echo Start failed. See errors above.
    pause
)
