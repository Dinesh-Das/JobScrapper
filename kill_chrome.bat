@echo off
echo Killing all Chrome and ChromeDriver processes...
taskkill /F /IM chrome.exe /T 2>nul
taskkill /F /IM chromedriver.exe /T 2>nul
echo Done!
timeout /t 2
