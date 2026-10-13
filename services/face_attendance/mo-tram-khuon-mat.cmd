@echo off
setlocal
title Tram cham cong khuon mat
rem Optional private key selected explicitly by the user; never print its contents.
if not defined GOOGLE_APPLICATION_CREDENTIALS if exist "%~dp0private\firebase-admin.json" set "GOOGLE_APPLICATION_CREDENTIALS=%~dp0private\firebase-admin.json"
echo Khoi dong tram camera, nhan dien va API van tay tren laptop.
echo Che do demo: chi thu tren laptop, khong ghi cong len Firebase.
echo Che do firebase: cho phep Admin dang ky khuon mat tu app.
echo Neu dung Firebase: xem README.md de dat khoa rieng vao private\firebase-admin.json.
echo Can giu cua so nay mo. Bam Ctrl+C de dung an toan.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" station
set "taskStationExit=%errorlevel%"
echo.
if not "%taskStationExit%"=="0" echo Khong khoi dong duoc. Xem thong bao ben tren va README.md.
pause
exit /b %taskStationExit%
