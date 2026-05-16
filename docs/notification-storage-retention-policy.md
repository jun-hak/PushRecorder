# Notification Storage Retention Policy

PushRecorder keeps the existing append-only `notifications` event log: POSTED
and REMOVED rows remain separate historical rows. Cleanup is bounded by local
Room policies so high-volume devices do not need manual app restarts to recover
storage.

## Historical Notification Rows

`NotificationRetentionPolicy.Default` applies both limits:

- age limit: 90 days
- count limit: 200,000 rows

At about 10,000 notifications per day, the POSTED/REMOVED model can produce
about 20,000 historical rows per day, so the row cap is expected to bound
storage before the age limit on very active devices.

Cleanup deletes rows older than the configured age cutoff and then trims oldest
overflow rows beyond the configured count limit. It is lifecycle-safe for the
current two-row event log:

- Active POSTED rows are preserved even when they are older than the cutoff or
  outside the newest-row cap, because dropping them would break later REMOVED
  detection.
- If a retained POSTED row is closed by an adjacent terminal row
  (`REMOVED`/`CLICKED`) whose clock timestamp falls outside the cleanup window,
  that terminal row is preserved so the POSTED row does not become active again.
- If a retained terminal row closes an adjacent POSTED row outside the cleanup
  window or row cap, that POSTED row is preserved so the retained terminal event
  is not orphaned from the lifecycle it completed.

These lifecycle-pair exceptions can keep a small number of extra rows beyond the
configured count limit. They do not rewrite rows or collapse POSTED/REMOVED into
a single lifecycle record.

Tests may inject a custom `NotificationRetentionPolicy` to disable either the
age limit or the count limit, but at least one limit must be configured.

## Pending Event Journal Rows

`NotificationEventJournalRetentionPolicy.Default` bounds durable pending work:

- pending age limit: 7 days
- pending row limit: 50,000 rows
- replay batch size: 2,000 rows

Pending rows are deleted after successful downstream processing. Rows that
cannot be processed are retried with capped exponential backoff and are eligible
for cleanup after the pending journal retention policy.

## Cleanup Scheduling

`NotificationStorageCleanupScheduler` runs notification and journal cleanup from
background coroutine-scope work. Requests are coalesced and throttled to a
default six-hour interval so cleanup is automatic during app startup, app
foreground entry, ViewModel startup, long-lived listener sessions, and the
periodic WorkManager entry point without running database deletes from the
Android notification callbacks.

`NotificationStorageCleanupWorker` registers unique periodic WorkManager work
named `notification-storage-cleanup` during application startup. The worker uses the same `NotificationStorageCleanupScheduler`
as the listener and application lifecycle callbacks, so notification cleanup and pending journal cleanup keep one throttling/coalescing policy
instead of separate deletion paths. The periodic
request uses the same default six-hour interval as the in-process scheduler and
lets AndroidX WorkManager provide OS-managed background retries when cleanup
fails.

Cleanup now has both deterministic in-process recovery points and a platform
scheduled fallback. It runs on app process startup, app foreground entry, service startup, ViewModel startup, after queued event processing, and from periodic WorkManager execution.
The remaining tradeoff is that WorkManager timing is opportunistic and Android may defer periodic work
for battery or scheduling reasons, so foreground/listener triggers remain important for prompt cleanup during active recording sessions.

## Instrumentation Verification

This hardening pass adds Room migration coverage and instrumented DAO/query-plan
coverage under `app/src/androidTest`. `connectedDebugAndroidTest` was attempted
on 2026-05-17 with a project-local Gradle home:

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --no-daemon connectedDebugAndroidTest
```

The command did not reach device or emulator execution. The Gradle wrapper first
needed `gradle-8.11.1-bin.zip`, but this sandbox cannot resolve
`services.gradle.org`, so the run failed with `UnknownHostException:
services.gradle.org`. The migration and instrumented test changes therefore
still require a connected-device `connectedDebugAndroidTest` run in an
environment with the Gradle distribution available.
