@echo off
rem Starts the app as a single process: builds the frontend into the backend's static
rem folder, then runs the backend, which serves both the API and the screen on :8080.
rem Close this window (or press Ctrl+C) to stop it.
title Ornn Build Timing Planner
cd /d "%~dp0"

where npm >nul 2>nul
if errorlevel 1 (
  echo [erro] npm nao encontrado. Rode "nvm use 24" uma vez e tente de novo.
  pause
  exit /b 1
)

echo === Gerando a tela (frontend) ===
pushd frontend
if not exist node_modules (
  call npm install || goto :falhou
)
call npm run build || goto :falhou
popd

echo.
echo === Ligando o servidor em http://localhost:8080 ===
echo O navegador abre sozinho em alguns segundos. Feche esta janela para desligar.
echo.
start "" /min cmd /c "timeout /t 15 /nobreak >nul & start http://localhost:8080"

cd backend
call "%~dp0backend\mvnw.cmd" spring-boot:run
pause
exit /b 0

:falhou
popd
echo.
echo [erro] Falha ao gerar o frontend.
pause
exit /b 1
