@echo off
chcp 65001 >nul
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Cần cài Node.js phiên bản 20 trở lên để mở web.
  pause
  exit /b 1
)
echo Mở trình duyệt tại http://localhost:4173 sau khi máy chủ khởi động.
echo Giữ cửa sổ này mở trong khi dùng web. Nhấn Ctrl+C để dừng.
node scripts/server.mjs
pause
