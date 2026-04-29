# 📋 Enumerati del Progetto — Payment Microservice

Tutti gli enum si trovano nel package:  
`com.microservices.payment.model.enums`

---

## 1. `PaymentStatus` — Ciclo di vita del pagamento

| Valore | Descrizione |
|---|---|
| `PENDING` | Pagamento creato, non ancora inviato al gateway |
| `INITIATED` | Inviato al gateway, in attesa di risposta |
| `REQUIRES_ACTION` | Richiede azione utente (es. 3DS/SCA — obbligatorio in Europa per PSD2) |
| `REQUIRES_CAPTURE` | Autorizzato ma non ancora catturato (pre-autorizzazione) |
| `AUTHORIZED` | Fondi riservati, cattura in sospeso |
| `PROCESSING` | Gateway sta elaborando la transazione |
| `ON_HOLD` | Bloccato per revisione manuale o anti-frode |
| `CAPTURED` | Fondi catturati (flusso con cattura separata) |
| `COMPLETED` | Pagamento completato e liquidato |
| `REFUND_PENDING` | Rimborso richiesto, in attesa del gateway |
| `PARTIALLY_REFUNDED` | Rimborso parziale emesso |
| `REFUNDED` | Rimborso totale emesso |
| `DECLINED` | Rifiutato dalla banca o dall'emittente della carta |
| `FAILED` | Errore tecnico o del gateway |
| `EXPIRED` | Link/sessione di pagamento scaduta prima del completamento |
| `CANCELLED` | Cancellato dall'utente o dal merchant |
| `CHARGEBACK` | Il cliente ha aperto una contestazione con la propria banca |
| `CHARGEBACK_WON` | Il merchant ha vinto la disputa |
| `CHARGEBACK_LOST` | Il merchant ha perso la disputa |

---

## 2. `PaymentMethod` — Metodi di pagamento

### 🌍 Comuni (entrambi i mercati)

| Valore | Descrizione |
|---|---|
| `CREDIT_CARD` | Carta di credito (Visa, Mastercard, Amex) |
| `DEBIT_CARD` | Carta di debito generica |
| `PAYPAL` | PayPal — disponibile in entrambi i mercati |
| `APPLE_PAY` | Wallet digitale Apple |
| `GOOGLE_PAY` | Wallet digitale Google/Android |

### 🇵🇪 Perù

| Valore | Descrizione |
|---|---|
| `YAPE` | App di pagamento mobile BCP — la più diffusa in Perù |
| `PLIN` | Pagamento mobile (alleanza BBVA, Interbank, Scotiabank, BanBif) |
| `PAGO_EFECTIVO` | Voucher pagabile in contanti presso agenti (KasNet, Western Union…) |
| `SAFETY_PAY` | Pagamento in contanti o bonifico tramite rete agenti |
| `NIUBIZ` | Principale gateway acquirer in Perù (ex VisaNet Peru) |
| `CULQI` | Fintech peruviana — carte, Yape, PagoEfectivo |
| `IZIPAY` | Gateway online e POS di Izipay SA |
| `BCP_TRANSFER` | Bonifico diretto Banco de Crédito del Perú |
| `INTERBANK_TRANSFER` | Bonifico diretto Interbank |
| `BBVA_TRANSFER_PE` | Bonifico diretto BBVA Peru |
| `SCOTIABANK_TRANSFER` | Bonifico diretto Scotiabank Peru |

### 🇮🇹 Italia / 🇪🇺 Europa

| Valore | Descrizione |
|---|---|
| `SEPA_CREDIT_TRANSFER` | Bonifico standard europeo (zona SEPA) |
| `SEPA_DIRECT_DEBIT` | Addebito diretto ricorrente (zona SEPA) |
| `BONIFICO` | Bonifico bancario classico italiano |
| `BANCOMAT` | Rete nazionale italiana carte di debito (PagoBancomat) |
| `NEXI` | Processore carte italiano (ex CartaSi) |
| `POSTEPAY` | Carta prepagata Poste Italiane |
| `SATISPAY` | App di pagamento mobile italiana |
| `SCALAPAY` | BNPL italiano (Buy Now Pay Later) |
| `KLARNA` | BNPL europeo (Pay Later, Slice It) |
| `STRIPE` | Gateway internazionale — molto usato in Europa |
| `AMAZON_PAY` | Disponibile in tutta l'UE |

---

## 3. `Currency` — Valute (ISO 4217)

| Valore | Paese/Area | Descrizione |
|---|---|---|
| `PEN` | 🇵🇪 Perù | Sol Peruviano — valuta ufficiale |
| `USD` | 🇵🇪 Perù | Dollaro americano — largamente accettato in Perù |
| `EUR` | 🇮🇹🇪🇺 Italia/Eurozona | Euro — valuta ufficiale |
| `CHF` | 🇨🇭 Svizzera | Franco Svizzero — rilevante per pagamenti cross-border EU |
| `GBP` | 🇬🇧 UK | Sterlina — ancora rilevante per trasferimenti EU post-Brexit |

---

## 4. `PaymentGateway` — Provider/Gateway

| Valore | Mercato | Descrizione |
|---|---|---|
| `STRIPE` | 🌍 Entrambi | Gateway internazionale — forte in Europa |
| `PAYPAL` | 🌍 Entrambi | Disponibile in entrambi i mercati |
| `CULQI` | 🇵🇪 Perù | Gateway fintech peruviano |
| `NIUBIZ` | 🇵🇪 Perù | Maggiore acquirer in Perù (ex VisaNet) |
| `IZIPAY` | 🇵🇪 Perù | Gateway online e POS |
| `SAFETY_PAY` | 🇵🇪 Perù | Rete agenti cash/bank |
| `NEXI` | 🇮🇹 Italia | Processore carte italiano |
| `KLARNA` | 🇪🇺 Europa | BNPL europeo |
| `SCALAPAY` | 🇮🇹 Italia | BNPL italiano |
| `SATISPAY` | 🇮🇹 Italia | Pagamento mobile italiano |
| `POSTEPAY` | 🇮🇹 Italia | Sistema Poste Italiane |
| `ADYEN` | 🇪🇺 Europa | Acquirer globale — ottima copertura EU enterprise |

---

## 6. `CardBrand` — Circuito carta di pagamento

| Valore | Descrizione |
|---|---|
| `VISA` | Circuito Visa — dominante in Perù e molto usato in Europa |
| `MASTERCARD` | Circuito Mastercard — globale |
| `AMERICAN_EXPRESS` | American Express — business/premium |
| `DINERS_CLUB` | Diners Club — accettato in Europa e America Latina |
| `DISCOVER` | Discover — USA, accettato in alcuni paesi EU |
| `JCB` | Japan Credit Bureau — accettato a livello globale |
| `UNIONPAY` | China UnionPay — in crescita globale |
| `BANCOMAT` | Rete nazionale italiana carta di debito (PagoBancomat) |
| `NEXI` | Carte emesse da Nexi (ex CartaSi) — Italia |
| `UNKNOWN` | Circuito non riconosciuto o non applicabile |


| Valore | Descrizione |
|---|---|
| `PERU` | Mercato domestico peruviano |
| `ITALY` | Mercato domestico italiano |
| `EUROPE_SEPA` | Cross-border UE (zona SEPA) |
| `INTERNATIONAL` | Qualsiasi transazione non coperta dai precedenti |
