@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
python -m pip install fastapi uvicorn python-multipart qrcode[pil] aiofiles pillow -q
python windows\app.py
if errorlevel 1 pause
