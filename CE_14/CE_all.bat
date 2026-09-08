@echo off
echo ====================================================
echo PHASE 1: Running Data Preprocessing...
echo ====================================================
call mvn clean compile exec:java "-Dexec.mainClass=DataPreprocessingWorkflow"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Preprocessing failed! Stopping workflow.
    pause
    exit /b 1
)

echo.
echo ====================================================
echo PHASE 2: Running Main Workflow...
echo ====================================================
call mvn exec:java "-Dexec.mainClass=com.engine.workflow.MainWorkflow" "-Dexec.args=workflow_config.json"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Main workflow failed!
    pause
    exit /b 1
)

echo.
echo ====================================================
echo ALL STEPS COMPLETED SUCCESSFULLY!
echo ====================================================
pause