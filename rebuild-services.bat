@echo off
REM ============================================================
REM  Build lai .jar (Maven) roi dong goi Docker + khoi dong lai
REM  Cho: booking-service, tour-catalog-service, analytics-service
REM
REM  QUAN TRONG: Dockerfile chi COPY target/*.jar (khong tu bien dich),
REM  nen PHAI chay Maven package truoc de tao .jar moi.
REM
REM  Yeu cau: Docker Desktop dang chay + da cai Maven (lenh 'mvn')
REM ============================================================
cd /d D:\Tourism_Microservices

echo.
echo === [1/3] Maven build .jar moi (co the mat vai phut)...
call mvn -DskipTests -pl tour-catalog-service,booking-service,analytics-service -am package
if errorlevel 1 goto :err

echo.
echo === [2/3] Docker build image tu .jar moi...
docker compose build tour-catalog-service booking-service analytics-service
if errorlevel 1 goto :err

echo.
echo === [3/3] Khoi dong lai container (force-recreate)...
docker compose up -d --force-recreate --no-deps tour-catalog-service booking-service analytics-service
if errorlevel 1 goto :err

echo.
echo === Trang thai hien tai:
docker compose ps tour-catalog-service booking-service analytics-service

echo.
echo === HOAN TAT. Reload lai trang de kiem tra. ===
pause
exit /b 0

:err
echo.
echo *** LOI: build hoac khoi dong that bai.
echo *** Neu bao 'mvn' khong nhan dien: cai Maven hoac build bang IntelliJ
echo *** (Maven panel -^> Lifecycle -^> package) roi chay lai file .bat nay.
pause
exit /b 1
