@echo off
setlocal
cd /d "%~dp0"
node -e "const s=require('net').createServer();s.on('error',()=>process.exit(1));s.listen(4173,'127.0.0.1',()=>s.close());"
if errorlevel 1 (
  echo Cong 4173 dang duoc dung. Dung cua so web cu bang Ctrl+C, roi mo lai tep nay.
  pause
  exit /b 1
)
set "WEB_ADMIN_MOBILE_CAMERA=1"
echo Mo camera trong app Admin: nhap dia chi HTTPS va ma SHA-256 hien ben duoi.
echo Giu cua so nay mo. Laptop va dien thoai can cung mang Wi-Fi.
node scripts\server.mjs
pause
