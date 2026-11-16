# ADR 001: PostgreSQL authority with bounded Redis admission

Status: accepted for baseline. Correctness and explainable recovery outweigh speculative Redis-first write speed. JDBC
SQL owns transaction predicates; no distributed lock is inventory authority. Kafka carries facts using outbox/inbox. No
token pools or SKU queues are enabled without evidence of contention beyond the tested baseline. Availability is
sacrificed when Redis is uncertain; PostgreSQL is still required for every successful reservation. A single hot pool
remains bounded by database lock turnover. See architecture comparison and verification for evidence limits.

Spring Boot 3.5.16 matches sibling projects. Official requirements support Java 17–25, including Java
21: https://docs.spring.io/spring-boot/3.5/system-requirements.html . Dependency versions for PostgreSQL JDBC, Flyway,
Lettuce, Spring Kafka, Micrometer and Testcontainers are managed by that tested Boot BOM. Maven's Java runtime must
explicitly be 21 here because the host Maven default is Java 27.
