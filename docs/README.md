# 💳 Payment Microservice — Project Documentation

## Indice / Table of Contents

| Documento | Descrizione |
|---|---|
| [ENUMS.md](./ENUMS.md) | Tutti gli enumerati del progetto con spiegazione per ogni valore |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Decisioni architetturali e strategia di riusabilità multi-progetto |
| [PAYMENTS-BY-REGION.md](./PAYMENTS-BY-REGION.md) | Metodi di pagamento supportati per ogni paese/regione |

---

## Panoramica del Progetto

**Nome:** `payment-service`  
**GroupId:** `com.microservices`  
**Versione Java:** 21 (Amazon Corretto)  
**Framework:** Spring Boot 3.5.x  
**Database:** PostgreSQL + Liquibase  
**Documentazione API:** OpenAPI 3 (Swagger UI)  

---

## Mercati Supportati (attualmente)

| Paese | Stato |
|---|---|
| 🇵🇪 Perù | ✅ Attivo (mercato primario) |
| 🇮🇹 Italia | 🔜 Pianificato |
| 🇪🇺 Europa (SEPA) | 🔜 Pianificato |

---

## Stack Tecnologico

| Layer | Tecnologia |
|---|---|
| Runtime | Java 21 — Amazon Corretto |
| Framework | Spring Boot 3.5 |
| Security | Spring Security |
| Persistenza | Spring Data JPA + PostgreSQL |
| Migrazioni DB | Liquibase |
| API Docs | SpringDoc OpenAPI 3 |
| Build tool | Maven |
| Boilerplate | Lombok |
| Validazione | Jakarta Validation (Bean Validation 3) |
