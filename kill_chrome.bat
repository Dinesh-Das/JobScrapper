@echo off
echo Killing leftover ChromeDriver processes from the scraper...
echo (Does NOT kill your regular Chrome windows.)
taskkill /F /IM chromedriver.exe /T 2>nul
echo Done!
timeout /t 2
