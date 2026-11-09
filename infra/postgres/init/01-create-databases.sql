-- Runs once, the first time the postgres volume is created (docker-entrypoint-initdb.d).
-- One database per microservice: services never read each other's tables.
-- To replay it after changing this file: docker compose down -v (this DELETES the data).
CREATE DATABASE event;
CREATE DATABASE booking;
CREATE DATABASE customer;
CREATE DATABASE payment;
