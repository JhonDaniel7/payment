# 🌍 Metodi di Pagamento per Regione — Payment Microservice

Mappa di quali metodi di pagamento, valute e gateway sono attivi per ogni mercato.

---

## 🇵🇪 Perù

| Categoria | Dettaglio |
|---|---|
| **Valuta principale** | PEN (Sol Peruviano) |
| **Valuta secondaria** | USD (accettato ovunque) |
| **Gateway primario** | Culqi / Niubiz |
| **Normativa** | SBS (Superintendencia de Banca y Seguros) |

### Metodi di Pagamento Attivi

| Metodo | Tipo | Popolarità |
|---|---|---|
| `YAPE` | Mobile payment (BCP) | ⭐⭐⭐⭐⭐ Dominante |
| `PLIN` | Mobile payment (BBVA/Interbank) | ⭐⭐⭐⭐ |
| `PAGO_EFECTIVO` | Voucher cash | ⭐⭐⭐⭐ (bancarizzati assenti) |
| `CREDIT_CARD` | Visa, Mastercard | ⭐⭐⭐ |
| `DEBIT_CARD` | Visa Débito, Mastercard Débito | ⭐⭐⭐ |
| `NIUBIZ` | Gateway card acquiring | ⭐⭐⭐ |
| `CULQI` | Gateway fintech locale | ⭐⭐⭐ |
| `IZIPAY` | Gateway POS/online | ⭐⭐ |
| `SAFETY_PAY` | Cash via agenti | ⭐⭐ |
| `BCP_TRANSFER` | Bonifico BCP | ⭐⭐ |
| `INTERBANK_TRANSFER` | Bonifico Interbank | ⭐⭐ |
| `PAYPAL` | Internazionale | ⭐ |
| `GOOGLE_PAY` | Mobile wallet | ⭐ |

---

## 🇮🇹 Italia

| Categoria | Dettaglio |
|---|---|
| **Valuta** | EUR (Euro) |
| **Gateway primario** | Nexi / Stripe |
| **Normativa** | PSD2 (obbligo 3DS/SCA), Banca d'Italia |

### Metodi di Pagamento Attivi

| Metodo | Tipo | Popolarità |
|---|---|---|
| `CREDIT_CARD` | Visa, Mastercard, Amex | ⭐⭐⭐⭐⭐ |
| `BANCOMAT` | Carta debito nazionale | ⭐⭐⭐⭐⭐ |
| `NEXI` | Processore carte italiano | ⭐⭐⭐⭐ |
| `POSTEPAY` | Prepagata Poste Italiane | ⭐⭐⭐⭐ |
| `SATISPAY` | Mobile payment italiano | ⭐⭐⭐⭐ |
| `SEPA_CREDIT_TRANSFER` | Bonifico SEPA | ⭐⭐⭐⭐ |
| `PAYPAL` | Internazionale | ⭐⭐⭐ |
| `SCALAPAY` | BNPL italiano | ⭐⭐⭐ |
| `KLARNA` | BNPL europeo | ⭐⭐⭐ |
| `STRIPE` | Gateway internazionale | ⭐⭐⭐ |
| `APPLE_PAY` | Mobile wallet | ⭐⭐⭐ |
| `GOOGLE_PAY` | Mobile wallet | ⭐⭐ |
| `AMAZON_PAY` | E-commerce | ⭐⭐ |
| `BONIFICO` | Bonifico classico | ⭐⭐ |

---

## 🇪🇺 Europa SEPA (Cross-border)

| Categoria | Dettaglio |
|---|---|
| **Valuta** | EUR (principale), CHF, GBP |
| **Gateway consigliato** | Stripe / Adyen |
| **Normativa** | PSD2 obbligatoria, GDPR |

### Metodi di Pagamento Attivi

| Metodo | Tipo | Note |
|---|---|---|
| `SEPA_CREDIT_TRANSFER` | Bonifico SEPA | Standard in tutta la zona SEPA |
| `SEPA_DIRECT_DEBIT` | Addebito diretto | Per pagamenti ricorrenti/abbonamenti |
| `CREDIT_CARD` | Visa, Mastercard, Amex | Con 3DS obbligatorio (PSD2) |
| `KLARNA` | BNPL | Disponibile in 20+ paesi EU |
| `PAYPAL` | Internazionale | Disponibile in tutta l'UE |
| `STRIPE` | Gateway | Ottima copertura europea |
| `ADYEN` | Gateway enterprise | Soluzione per grandi volumi |

---

## 📌 Note Normative Importanti

| Regione | Normativa chiave | Impatto sul codice |
|---|---|---|
| 🇪🇺 Europa | **PSD2 / SCA** | Obbligatorio `REQUIRES_ACTION` (3DS) per carte |
| 🇪🇺 Europa | **GDPR** | Nessun dato sensibile in chiaro nel DB |
| 🇵🇪 Perù | **SBS Circular 006-2022** | Logging transazioni obbligatorio |
| 🌍 Tutti | **PCI-DSS** | Mai salvare numeri di carta nel DB |
