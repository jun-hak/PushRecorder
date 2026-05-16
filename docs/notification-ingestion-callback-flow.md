# Notification Ingestion Callback Flow

Project-local path: `docs/notification-ingestion-callback-flow.md`

This note traces the synchronous work reachable from
`NotificationListenerService.onNotificationPosted` and
`NotificationListenerService.onNotificationRemoved`. It exists as the callback
responsiveness baseline for the performance-hardening work.

## Posted Callback Path

1. `NotificationListenerService.onNotificationPosted(sbn)` converts the Android
   `StatusBarNotification` to `NotificationCapture` with
   `sbn.toNotificationCapture()`.
2. `StatusBarNotification.toNotificationCapture()` reads framework notification
   fields and applies the bounded title/text/timestamp extraction policy.
3. `NotificationListenerAdapter.onNotificationPosted(capture)` wraps the
   capture in `NotificationProcessingCommand.Posted`.
4. `NotificationListenerService.enqueue(event)` delegates to
   `NotificationDurableEventEnqueuer.enqueue(event)`.
5. `NotificationDurableEventEnqueuer.enqueue(event)` performs non-suspending
   `Channel.trySend` into the bounded journal persistence queue and returns.

After step 5, `NotificationDurableEventEnqueuer` persists POSTED and REMOVED
events from a service-owned `Dispatchers.IO` coroutine. Successful journal
persistence then calls `NotificationProcessingQueue.enqueue(event)`, which uses
non-suspending `Channel.trySend` into the bounded processing queue. If journal
insert fails, the error is recorded through listener status and the foreground
event is rejected before it enters the processing queue. Room notification-row
writes, active-snapshot updates, app-info lookups, and journal acknowledgement
also run downstream from service-owned coroutine work, not synchronously from
the Android posted callback.

## Removed Callback Path

1. `NotificationListenerService.onNotificationRemoved(sbn)` converts the Android
   `StatusBarNotification` to `NotificationCapture` with
   `sbn.toNotificationCapture()`, then delegates to
   `NotificationListenerAdapter.onNotificationRemoved(capture, systemReason = null)`.
2. `NotificationListenerService.onNotificationRemoved(sbn, rankingMap, reason)`
   converts the Android `StatusBarNotification` to `NotificationCapture` with
   `sbn.toNotificationCapture()`, then delegates to
   `NotificationListenerAdapter.onNotificationRemoved(capture, systemReason = reason)`.
3. `NotificationListenerAdapter.onNotificationRemoved(capture, systemReason)`
   wraps the capture and optional Android removal reason in
   `NotificationRemovalCommand`, then emits `NotificationProcessingCommand.Removed`.
4. `NotificationListenerService.enqueue(event)` delegates to
   `NotificationDurableEventEnqueuer.enqueue(event)`.
5. `NotificationDurableEventEnqueuer.enqueue(event)` performs non-suspending
   `Channel.trySend` into the bounded journal persistence queue and returns.

After step 5, `NotificationDurableEventEnqueuer` persists the REMOVED event
from a service-owned `Dispatchers.IO` coroutine, preserving the callback
`systemReason` when Android provides one. Successful journal persistence then
calls `NotificationProcessingQueue.enqueue(event)`, which uses non-suspending
`Channel.trySend` into the bounded processing queue. `NotificationEventProcessor`
work, removal classification, `NotificationRepository.recordRemoved`,
active-snapshot updates, app-info lookups, and journal acknowledgement run from
service-owned coroutine work, not synchronously from either Android removed
callback.

## Callback-Reachable Journal Boundary

- `NotificationDurableEventEnqueuer.enqueue` calls `Channel.trySend` into the
  bounded journal persistence queue. It does not call Room or `runBlocking` from
  the Android callback thread.
- The journal worker is owned by the listener service coroutine scope and runs
  on `Dispatchers.IO`. It calls `journalRepository.journalIfRequired(command)`.
  For posted events, this reaches `NotificationEventJournalRepository`
  `journalIfRequired`, then `NotificationEventJournalDao.insert`, a Room insert
  into `notification_event_journal`.
- For removed events, the same background journal worker reaches
  `NotificationEventJournalRepository.journalIfRequired`, then
  `NotificationEventJournalDao.insert`, with `eventType = REMOVED` and the
  callback `systemReason` preserved when Android provides one.
- `NotificationListenerService.onDestroy` closes and drains the journal
  persistence queue and waits for startup replay work before closing the
  processing queue. The service job is cancelled after both queues have drained.

## Callback-Reachable Room Mutation Inventory

| Callback path | Synchronous Room insert | Synchronous Room update | Synchronous Room delete |
| --- | --- | --- | --- |
| Posted | None reached from the Android callback | None reached from the Android callback | None reached from the Android callback |
| Removed | None reached from the Android callback | None reached from the Android callback | None reached from the Android callback |

No callback-reachable Room insert, update, or delete remains in
`onNotificationPosted` or `onNotificationRemoved`. Journal writes are queued
behind `NotificationDurableEventEnqueuer.enqueue(event)`, and notification-row
writes are queued behind `NotificationProcessingQueue.enqueue(event)`.
No `notifications` table insert, update, or delete is reached synchronously from
the Android callbacks.

## Callback-Reachable Non-Room Persistence Inventory

| Callback path | Successful enqueue path | Rejection path |
| --- | --- | --- |
| Posted | No synchronous local persistence after `NotificationDurableEventEnqueuer.enqueue(event)` accepts the event with `Channel.trySend`. Journal and notification-row persistence run from service-owned coroutine work. | `NotificationDurableEventEnqueuer.reject` launches service-owned dispatcher work to call `NotificationListenerStatusRepository.markError`, which persists listener status through `SharedPreferences.edit { ... }`. This can happen when the journal persistence queue is not running, has been torn down, or is full, but the callback path returns before the status write runs. |
| Removed | No synchronous local persistence after `NotificationDurableEventEnqueuer.enqueue(event)` accepts the event with `Channel.trySend`. Journal and notification-row persistence run from service-owned coroutine work. | Same asynchronous `NotificationListenerStatusRepository.markError` / `SharedPreferences.edit { ... }` rejection-reporting path as POSTED events. |

Rejected-event status persistence is intentionally reported from the listener
service coroutine scope so the normal high-volume callback path and the bounded
backpressure path both avoid waiting for Room or status persistence from the successful Android callback path.

## Intentionally Remaining Blocking Persistence Calls

The following persistence calls are intentionally still synchronous at their
own call sites. They are acceptable because the Android notification callbacks
only reach `StatusBarNotification.toNotificationCapture()`, the listener
adapter, `NotificationDurableEventEnqueuer.enqueue(event)`, and bounded
`Channel.trySend`; the listed calls run from service startup/shutdown,
connection events, or service-owned coroutine work after the callback returns.

| Persistence call site | Blocking persistence work | Unreachable-from-callback evidence | Justification |
| --- | --- | --- | --- |
| `NotificationDurableEventEnqueuer.replayPendingEvents` | In service-scope `Dispatchers.IO` work, `journalRepository.deleteExpiredPendingEvents()` and `journalRepository.pendingEvents()` reach Room delete/read calls on `notification_event_journal`. | It is called from `NotificationListenerService.onCreate`, not from `onNotificationPosted` or either `onNotificationRemoved` overload. | Startup replay keeps already-journaled events durable across process death and bounds stale pending rows before replaying them. |
| `NotificationDurableEventEnqueuer.persistThenEnqueue` | In the journal worker, `journalRepository.journalIfRequired(command)` reaches `NotificationEventJournalDao.insert`. | The callbacks call `NotificationDurableEventEnqueuer.enqueue(event)`, which only checks queue state and calls `commands.trySend(command)`; the insert is inside the worker started by `scope.launch(dispatcher)`. | A durable local journal protects accepted POSTED/REMOVED events before downstream notification-row processing without blocking callback threads. |
| `NotificationListenerService.processQueuedEvent` | `notificationEventJournalRepository.acknowledge(event)` deletes the processed journal row through Room. | `processQueuedEvent` is invoked by `NotificationProcessingQueue.start` from its service-owned consumer coroutine after `NotificationProcessingQueue.enqueue(event)` accepts the command with `events.trySend(event)`. | Acknowledgement only happens after `processEvent(event)` succeeds, preserving retry semantics for failed or rejected queued events. |
| `NotificationListenerService` queue failure handler | `notificationEventJournalRepository.markProcessingFailed(event, error)` reads retry count and updates retry metadata through Room. | The handler is passed as `NotificationProcessingQueue.onFailed` and is invoked only when the queue consumer catches a processing exception. It is not referenced by the Android callback bodies. | Retry metadata prevents tight replay loops and keeps already-journaled failed events available for bounded later replay. |
| `NotificationStorageCleanupScheduler.requestCleanup` | The launched cleanup coroutine calls `NotificationRepository.deleteExpiredNotifications()` and `NotificationEventJournalRepository.deleteExpiredPendingEvents()`, which delete/trim Room rows. | `requestStorageCleanup(force = true)` is called during `onCreate`; the non-forced call happens after queued processing and journal acknowledgement. The scheduler itself launches cleanup work and returns. | Automatic retention enforces local storage bounds during long-lived listener sessions instead of relying on manual app restarts. |

Storage cleanup is requested after queued event processing and service startup so long-lived sessions enforce retention without waiting for a manual restart.
| `NotificationEventProcessor.process` through `NotificationCaptureProcessor.processAccepted` and removed-event handling | `NotificationRepository.recordPosted`, `recordRemoved`, `getActiveNotificationByKey`, `hasRecordedEventJournalId`, and `hasTerminalEventForLatestLifecycle` reach Room notification-row inserts and reads. | `NotificationEventProcessor.process` is called by `NotificationListenerService.processEvent`, which is only reached from `NotificationProcessingQueue` worker execution after the processing queue accepts an event. | These calls preserve the existing two-row POSTED/REMOVED event-log model and duplicate/journal idempotency checks while keeping callback work bounded. |
| `NotificationReconciler.processReconcile` and `refreshActiveSnapshot` | `NotificationRepository.reconcileActiveNotifications()` and `getActiveNotificationByKey()` perform Room reads and synthetic removal inserts. | Reconcile commands come from listener connection/startup scheduling and are processed by the same `NotificationProcessingQueue` worker path, not directly by notification posted/removed callbacks. | Reconciliation repairs missed removals and stale active snapshots without changing foreground callback semantics. |
| `AppRegistryRepository.packageSnapshot`, `recordObservedPackage`, and `syncInstalledApps` | App-record reads and upserts through `AppRecordDao` persist local package metadata. | `packageSnapshot` and `recordObservedPackage` run inside queued event processing; `syncInstalledApps` is launched from `NotificationListenerService.onCreate`. | App labels/icons can be resolved and cached without making callback delivery wait for package manager or Room work. |
| `NotificationListenerStatusRepository.updateStatus` | `SharedPreferences.edit { ... }` persists listener status snapshots for service, queue, processing, and error state. | Foreground success status methods such as `markPosted`, `markRemoved`, `markEventQueued`, `markEventProcessed`, and `markEventFailed` are called from service-owned journal/processing coroutines. Rejection status uses `NotificationDurableEventEnqueuer.reject`, which launches dispatcher work before calling `statusRecorder.markError(message)`. Service lifecycle and connection status calls are reached from `onCreate`, `onDestroy`, `onListenerConnected`, and `onListenerDisconnected`, not from posted/removed callbacks. | Status persistence is small local diagnostic state used to surface queue/backlog/error health; keeping it synchronous at the repository boundary avoids losing state updates while the callback path remains non-blocking. |

## Bounded Queue Rejection Paths

The listener currently has two bounded `Channel.trySend` boundaries and no other callback-reachable `offer` or suspending `send` path.

| Boundary | Rejection condition | Acknowledge side effect | Drop or retry side effect | Processed-state side effect |
| --- | --- | --- | --- | --- |
| Journal persistence queue, `NotificationDurableEventEnqueuer.enqueue` | `isAcceptingEvents` is false after teardown | No journal acknowledgement occurs because no journal row exists for this enqueue attempt. | The command is not journaled and is not sent to the processing queue. POSTED/REMOVED callback data from this rejected attempt is dropped; `statusRecorder.markError` and logging are scheduled on the service dispatcher. | `markEventQueued`, `markEventProcessed`, `markPosted`, and `markRemoved` are not called. |
| Journal persistence queue, `NotificationDurableEventEnqueuer.enqueue` | Journal worker is not running | No journal acknowledgement occurs because no journal row exists for this enqueue attempt. | The command is not journaled and is not sent to the processing queue. POSTED/REMOVED callback data from this rejected attempt is dropped; `statusRecorder.markError` and logging are scheduled on the service dispatcher. | `markEventQueued`, `markEventProcessed`, `markPosted`, and `markRemoved` are not called. |
| Journal persistence queue, `NotificationDurableEventEnqueuer.enqueue` | `commands.trySend(command)` fails because the bounded journal persistence queue is full or closed | No journal acknowledgement occurs because no journal row exists for this enqueue attempt. | The command is not journaled and is not sent to the processing queue. POSTED/REMOVED callback data from this rejected attempt is dropped; `statusRecorder.markError` and logging are scheduled on the service dispatcher. | `markEventQueued`, `markEventProcessed`, `markPosted`, and `markRemoved` are not called. |
| Processing queue, `NotificationProcessingQueue.enqueue` | `isAcceptingEvents` is false after listener teardown | No processing acknowledgement is performed by the processing queue. If the command already has `eventJournalId`, `NotificationDurableEventEnqueuer.enqueueJournaledCommand` leaves the existing journal row unchanged. | Journaled POSTED/REMOVED commands remain in `notification_event_journal` with their existing due retry state. Non-journaled `Reconcile` commands have no journal row, so the reconcile attempt is dropped after status/log recording. | `onQueued` and `onProcessed` are not called; foreground `markPosted`/`markRemoved` is not reached. |
| Processing queue, `NotificationProcessingQueue.enqueue` | Processor job is not running | No processing acknowledgement is performed by the processing queue. If the command already has `eventJournalId`, the durable enqueuer leaves the existing journal row unchanged for replay. | Journaled POSTED/REMOVED commands remain pending for replay. Non-journaled `Reconcile` commands are dropped because there is no journal row to retry. | `onQueued`, `onProcessed`, `markPosted`, and `markRemoved` are not called. |
| Processing queue, `NotificationProcessingQueue.enqueue` | `events.trySend(event)` fails because the bounded processing queue is full or closed | No processing acknowledgement is performed by the processing queue. If the command already has `eventJournalId`, the durable enqueuer leaves the existing journal row unchanged for replay. | Journaled POSTED/REMOVED commands remain pending for replay under `RejectNewestKeepDurableJournal` without being marked as processing failures. Non-journaled `Reconcile` commands are dropped because there is no journal row to retry. | `onQueued`, `onProcessed`, `markPosted`, and `markRemoved` are not called for the rejected command. |

When the processing queue rejects a journaled foreground event, the foreground event is rejected before it enters the processing queue. No journal acknowledgement occurs because no journal row exists for non-journaled reconcile commands; journaled POSTED/REMOVED commands remain pending for replay.

For accepted processing-queue commands, `NotificationListenerService` calls
`notificationEventJournalRepository.acknowledge(event)` only after
`processEvent(event)` succeeds. If `processEvent` throws, the processing queue
calls `onFailed`, the journal row is kept, and retry metadata is updated with
`markProcessingFailed`; the failed command is not counted as processed.

## Compatibility Notes

The current POSTED/REMOVED event-log model is preserved. This trace documents
the callback boundary; it does not replace lifecycle event rows with a single
mutable notification row.

Pending journal rows now carry explicit retry metadata: `retryCount`,
`lastAttemptAt`, `nextAttemptAt`, and `lastError`. Startup replay only reads
rows whose `nextAttemptAt` is due and limits each replay batch. Downstream
processing failures update this metadata; processing-queue rejections leave
already-journaled rows unchanged so they remain due and retained for replay.
Unprocessed journal rows are retained by a local policy, currently seven days
and at most 50,000 pending rows, before they are eligible for cleanup.
