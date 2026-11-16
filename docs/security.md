# Security

Production OIDC validates signature through issuer JWKS, issuer, time and configured audience. Token subject supplies
customer identity; clients cannot set it. Roles claim uses CUSTOMER/ADMIN. Customer eligibility is the issuer's CUSTOMER
grant, not merely possession of a token. Confirm/cancel/status enforce ownership; administration requires ADMIN. Tenant
scope is deliberately single tenant; multi-tenancy requires composite tenant constraints, issuer namespace and row
ownership before enablement.

Demo uses explicit profile-scoped Basic accounts and in-memory hashes. Use only on loopback. Browser credentials remain
in memory and are not persisted. Production requires TLS ingress, CSP/security headers, strict body limits, trusted
proxy configuration, WAF, device/session policies, customer identity verification and gateway quotas on every endpoint.
Redis quotas are not sufficient bot prevention. CSRF is disabled for stateless bearer-token production APIs; do not
deploy the Basic demo to untrusted networks.

PostgreSQL runtime should have DML-only role; provision a separate Flyway migration identity or run migrations as a
deployment job. The sample local role owns schema for convenience. Managed Redis requires TLS/ACL and noeviction; Kafka
requires TLS/SASL and topic ACLs. Supply credentials through external secret management into flashsale-runtime, never
committed manifests. JWT roles are trusted only from configured issuer. API errors redact SQL and internal exception
messages. Retain audited stock adjustment reason and inventory movements; adjustment audit includes SKU, warehouse and
authenticated actor; tamper-resistant external audit storage remains deployment work.

Idempotency prevents duplicate business consumption, not stolen-token misuse. Add gateway request limits and
token/session revocation. Neither device controls nor CAPTCHA establishes inventory correctness. Waiting-room signing,
external fraud scoring and global IP/device reputation are design extensions, not implemented capabilities.
