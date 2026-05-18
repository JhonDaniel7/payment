@echo off
echo Reset PostgreSQL Docker container...

docker rm -f payment-microservizio-db 2>nul

docker run -d --name payment-microservizio-db ^
-p 5436:5432 ^
-e POSTGRES_DB=payment_db ^
-e POSTGRES_USER=admin ^
-e POSTGRES_PASSWORD=admin ^
postgres:15

echo Database ready.
pause