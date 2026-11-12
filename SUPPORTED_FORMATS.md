# Supported email formats

| Format | Enabled | Evidence |
|---|---|---|
| MONEYTRACK-SYNTHETIC-V1 | Demo/tests only | Invented fixtures in core tests |
| Real bank/UPI email templates | No | Awaiting redacted examples and validation |

The Gmail authorization, API transport, pagination and history checkpoints are implemented, but the live parser registry deliberately returns no records. Supplying OAuth credentials alone does not enable live financial tracking. Do not market this preview as supporting a bank.

Add a parser implementing `EmailParser` and register it in `EmailParserRegistry.live` only after testing confirmation, failed, pending, reversal, OTP, request, promotion, malformed amount, missing reference and delayed-email examples. Verify sender provenance as supporting evidence; an email is not authoritative proof of settlement. Never save a full body. Never infer transaction time from the email date without marking it unknown.

Synthetic fixture format (not a real bank template):

```text
MONEYTRACK-SYNTHETIC-V1
amount=123.45
direction=DEBIT
status=SUCCESSFUL
account=****1234
reference=SYNTHETIC-001
merchant=Example shop
time=2026-11-10T10:00:00Z
```

Only INR is supported in this preview. Missing reference or time remains missing. Minor-unit conversion rejects fractional paise. Gmail HTML-only formats are not parsed yet.
