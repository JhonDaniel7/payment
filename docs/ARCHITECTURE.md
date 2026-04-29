# 🏗️ Architettura & Strategia di Riusabilità — Payment Microservice

---

## ✅ È una buona idea fare un microservizio riutilizzabile?

**Sì, è un'ottima idea** — ma solo se lo progetti bene fin dall'inizio.  
Farlo a posteriori è costoso. Farlo adesso, da zero, è il momento giusto.

Il pattern si chiama **"Configuration-Driven Microservice"**:  
> *un unico codebase, comportamento diverso per ogni deployment.*

---

## 🎯 Principi Architetturali adottati

### 1. Strategy Pattern — per i gateway di pagamento
Ogni gateway di pagamento (Culqi, Niubiz, Stripe, Nexi…) implementa la stessa interfaccia:

```
PaymentGatewayStrategy (interface)
    ├── CulqiGatewayStrategy        → 🇵🇪 Perù
    ├── NiubizGatewayStrategy       → 🇵🇪 Perù
    ├── StripeGatewayStrategy       → 🌍 Entrambi
    ├── NexiGatewayStrategy         → 🇮🇹 Italia
    └── KlarnaGatewayStrategy       → 🇪🇺 Europa
```

**Vantaggio:** aggiungere un nuovo paese = aggiungere una nuova implementazione,  
**senza toccare il codice esistente** (principio Open/Closed — SOLID).

---

### 2. Configuration by Profile — Spring Profiles per regione

```
application.yml           → configurazione base condivisa
application-peru.yml      → override per il Perù  (gateway=culqi, currency=PEN)
application-italy.yml     → override per l'Italia (gateway=nexi, currency=EUR)
application-europe.yml    → override per l'Europa SEPA
```

Il deployment in un paese specifico si gestisce con:
```bash
java -jar payment.jar --spring.profiles.active=peru
```

**Nessuna modifica al codice** tra un deploy e l'altro.

---

### 3. Feature Flags — abilitare/disabilitare metodi di pagamento

Nel file `application-{region}.yml` si abilitano solo i metodi rilevanti:

```yaml
# application-peru.yml
payment:
  enabled-methods: YAPE, PLIN, PAGO_EFECTIVO, CULQI, CREDIT_CARD
  default-currency: PEN
  active-gateway: CULQI
  region: PERU
```

```yaml
# application-italy.yml
payment:
  enabled-methods: NEXI, SATISPAY, SCALAPAY, SEPA_CREDIT_TRANSFER, CREDIT_CARD
  default-currency: EUR
  active-gateway: NEXI
  region: ITALY
```

---

### 4. Internazionalizzazione (i18n) — messaggi e validazioni

Tutti i messaggi di errore e risposta sono in file `.properties` separati per lingua:

```
resources/
  messages_es.properties   → Spagnolo (Perù)
  messages_it.properties   → Italiano
  messages_en.properties   → Inglese (default)
```

---

## 🗂️ Struttura Package consigliata

```
com.microservices.payment
├── config/              → SecurityConfig, OpenApiConfig, PaymentConfig
├── controller/          → PaymentController (REST endpoints)
├── service/
│   ├── PaymentService          → interfaccia
│   └── impl/
│       └── PaymentServiceImpl  → logica di business
├── gateway/             → Strategy Pattern per i gateway
│   ├── PaymentGatewayStrategy  → interfaccia
│   └── impl/
│       ├── CulqiGatewayStrategy
│       ├── NiubizGatewayStrategy
│       ├── StripeGatewayStrategy
│       └── NexiGatewayStrategy
├── model/
│   ├── entity/          → Payment.java (JPA entity)
│   ├── dto/             → PaymentRequestDTO, PaymentResponseDTO
│   └── enums/           → PaymentStatus, PaymentMethod, Currency, PaymentGateway, PaymentRegion
├── repository/          → PaymentRepository (Spring Data JPA)
├── exception/           → PaymentException, GlobalExceptionHandler
└── PaymentApplication.java
```

---

## ⚖️ Vantaggi e Rischi

### ✅ Vantaggi
- **Un solo codebase** da manutenere e testare
- **Deploy indipendenti** per paese — ogni progetto usa il suo profilo
- **Aggiungere un nuovo paese** non rompe i precedenti
- **Standardizzazione** tra i tuoi progetti — stessa API, stessa struttura DB
- **Riuso della documentazione, dei test, e del Liquibase changelog**

### ⚠️ Rischi da gestire
| Rischio | Mitigazione |
|---|---|
| Dipendenze specifiche di un paese che "inquinano" il codice | Usare Strategy Pattern + feature flags |
| Normative fiscali diverse (IVA italiana vs IGV peruviana) | Separare il calcolo fiscale in un modulo dedicato |
| Requisiti di sicurezza diversi (PSD2 in Europa, SBS in Perù) | Configurare la security per profilo |
| Il microservizio diventa troppo grande | Valutare in futuro un ulteriore split per gateway |

---

## 📌 Conclusione

> Stai adottando l'approccio corretto.  
> Progettare **da zero** per la riusabilità ti risparmierà **settimane di refactoring** in futuro.  
> La chiave è: **isola ciò che cambia** (gateway, valuta, metodi) dalla **logica comune** (status, entity, API).
