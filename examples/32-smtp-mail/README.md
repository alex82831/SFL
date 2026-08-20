# 32 — Sending mail

The **smtp** package: without configuration, `sfl build run` prints the
exact RFC 5322 message it would send — headers, `multipart/mixed` wrapping
a text+HTML `multipart/alternative`, a base64 CSV attachment, an encoded
UTF-8 subject. With a `.env` (copy `.env.example`) it connects for real:
STARTTLS (or implicit TLS on port 465), AUTH PLAIN/LOGIN — which the
driver refuses over plaintext — and sends.

The **dotenv** package supplies the configuration layer.

## Run

```bash
sfl build setup
sfl build run                     # preview mode
cp .env.example .env              # fill in a real relay to send
sfl build run
sfl build test
```
