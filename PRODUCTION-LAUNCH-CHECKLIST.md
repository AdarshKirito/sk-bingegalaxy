# Production Launch Checklist

## Release Gate

- `backend`: `mvn test` passes across the full reactor.
- `frontend`: `npm run build`, `npm run typecheck`, and `npm test -- --run` all pass.
- CI fails on dependency and image scan findings instead of allowing them with `|| true`.
- CI deploys immutable image tags only; no `latest` promotion path is used.

## Security

- No public payment simulation path is exposed to customers.
- Successful payment callbacks require verified gateway signatures.
- Customer payment lookups are owner-scoped; only admins can inspect arbitrary bookings.
- All runtime secrets come from `app-secrets` and `db-secrets`, not local defaults.
- Public traffic reaches the platform only through ingress and TLS.

## Payments

- Keep payment simulation disabled outside non-production environments.
- Set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `PAYMENT_CALLBACK_URL` in the target environment.
- Validate callback idempotency and failed-payment retry behavior in a non-local environment.

## Notifications

- Configure SMTP for production email delivery and verify real sends.
- Replace SMS and WhatsApp mocks with provider integrations or disable those channels in the UI.
- Add alerting for failed notification retries.

## Data and Migrations

- Verify Flyway migrations are applied cleanly for auth, booking, payment, and availability databases.
- Verify rollback-safe schema deployment for auth, booking, payment, and availability databases.
- Document backup and restore procedures for PostgreSQL and MongoDB.
- If `BACKUP_S3_BUCKET` is set, ensure the backup job image includes the `aws` CLI or your platform provides an equivalent object-store sync path.

## Kubernetes

- PostgreSQL runs on a managed HA service (RDS, Cloud SQL, Azure DB) or a multi-node operator cluster — NOT the single-node StatefulSet in `postgres.yml`.
- `app-config` provides `SPRING_CLOUD_CONFIG_URI`, config credentials, Kafka bootstrap servers, and tracing endpoint.
- `discovery-server`, `config-server`, and `api-gateway` all receive the secrets they require and run with non-root security contexts.
- `discovery-server` and `config-server` run with startup, readiness, and liveness probes plus at least two replicas.
- `mongodb` runs as a replica set and `notification-service` uses the replica-set connection string.
- `STORAGE_CLASS_NAME` is explicitly set for the target cluster before manifest rendering.
- Ingress is the only external entry point; service exposure is internal-only.
- Health probes and rollout verification succeed in the target cluster.

## Business Validation

- Customer registration, login, booking, payment, notification, refund, and admin operations are smoke-tested end to end.
- Reminder automation is verified against the real notification channel behavior you intend to ship.
- Support contact, account settings, and payment history flows are verified against production configuration.