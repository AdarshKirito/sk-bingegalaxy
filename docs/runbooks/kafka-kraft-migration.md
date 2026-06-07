# Kafka KRaft Migration Runbook

ZooKeeper-based Kafka is end-of-life in Apache Kafka 4.x (targeted 2025 H2).
Confluent's support timeline mirrors Apache's. This runbook documents the migration
path from ZooKeeper to KRaft for both the development environment and the Strimzi
production cluster.

---

## Timeline and urgency

| Milestone | Target |
|-----------|--------|
| Apache removes ZK support | Kafka 4.0 (2025 H2 est.) |
| Confluent Platform 8.x EOL for ZK | ~2026 |
| **Action required by** | **Q1 2026** |

Plan migration before it is forced. A forced migration under pressure is when things break.

---

## Development environment

### One-time: format KRaft storage

KRaft requires the log directories to be pre-formatted with a cluster UUID.
Run this once per developer machine (or wipe the volume):

```bash
# Generate a cluster UUID (same value as CLUSTER_ID in docker-compose.kraft.yml)
docker run --rm confluentinc/cp-kafka:7.6.0 \
  kafka-storage random-uuid
# → outputs e.g. "skbg-dev-kraft-cluster-01"

# Format the volume (docker will create it if absent)
docker run --rm -v skbg_kafka_kraft_data:/var/lib/kafka/data \
  confluentinc/cp-kafka:7.6.0 \
  kafka-storage format \
    --cluster-id skbg-dev-kraft-cluster-01 \
    --config /etc/kafka/kraft/broker.properties
```

### Start KRaft dev stack

```bash
# Use the override file — ZooKeeper container is suppressed via profile
docker compose -f docker-compose.yml -f docker-compose.kraft.yml up -d
```

### Verify

```bash
docker exec skbg-kafka-kraft kafka-metadata-quorum \
  --bootstrap-server localhost:9092 describe --status
# LeaderId should be 1, CurrentOffset > 0
```

---

## Production (Strimzi on Kubernetes)

Strimzi supports KRaft from operator version 0.39+ with Kafka 3.7+.

### Prerequisites

1. Strimzi operator upgraded to ≥ 0.39
2. Kafka cluster running ≥ 3.6 (KRaft migration bridge supported)
3. Full Kafka + ZooKeeper backup (snapshot all Kafka topics + ZK data)
4. Maintenance window of 2h minimum

### Step 1: Enable KRaft migration mode (dual-mode, non-destructive)

Edit `k8s/kafka.yml`:

```yaml
spec:
  kafka:
    version: 3.7.0
    metadataVersion: "3.7-IV4"
    config:
      # Enable migration — ZK still authoritative during this phase
      zookeeper.metadata.migration.enable: "true"
  zookeeper:
    replicas: 3   # keep running during migration
```

Apply and wait for rolling restart to complete:

```bash
kubectl apply -f k8s/kafka.yml -n sk-binge-galaxy
kubectl rollout status statefulset/skbg-kafka -n sk-binge-galaxy
```

### Step 2: Finalize KRaft (ZK no longer authoritative)

Once `kafka-metadata-quorum describe` shows all brokers registered in KRaft:

```bash
kubectl exec -n sk-binge-galaxy skbg-kafka-0 -- \
  kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status
```

Remove `zookeeper.metadata.migration.enable` and drop the `zookeeper` section:

```yaml
spec:
  kafka:
    version: 3.7.0
    metadataVersion: "3.7-IV4"
    # zookeeper.metadata.migration.enable removed
  # zookeeper: section removed
```

### Step 3: Decommission ZooKeeper

After all brokers confirm KRaft leadership, scale ZooKeeper to 0:

```bash
kubectl scale statefulset/skbg-zookeeper --replicas=0 -n sk-binge-galaxy
```

Monitor Kafka consumer lag for 30 minutes before deleting the ZooKeeper StatefulSet
and PVCs.

---

## Rollback

If migration fails before Step 2 (ZK still authoritative):

1. Remove `zookeeper.metadata.migration.enable` from Kafka config
2. Apply the reverted manifest — brokers will re-attach to ZooKeeper
3. No consumer offset data is lost (offsets are in Kafka, not ZK)

After Step 2 is finalized, rollback is not possible without restoring from the ZK snapshot.
This is why the backup in Step 0 is mandatory.

---

## Verification checklist

- [ ] All topics present: `kafka-topics.sh --list --bootstrap-server localhost:9092`
- [ ] Consumer groups intact: `kafka-consumer-groups.sh --list --bootstrap-server localhost:9092`
- [ ] No consumer lag spike (Grafana: Kafka Consumer Lag panel)
- [ ] Application health: all services show UP in Eureka dashboard
- [ ] E2E smoke test: create a booking, confirm payment, check confirmation email
