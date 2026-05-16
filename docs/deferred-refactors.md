# Deferred Refactors

Project-local path: `docs/deferred-refactors.md`

This list records PushRecorder architecture cleanup that is intentionally deferred
from phase 1. Phase 1 keeps notification capture behavior stable while splitting
the listener adapter, event processor, and reconcile policy boundaries.

## List

- [ ] id: DR-001 | area: notification-listener | phase: later | item: Move listener permission and connection state UI signals behind a smaller app-facing status model.
- [ ] id: DR-002 | area: reconcile-policy | phase: later | item: Make active-notification retry timing configurable after behavior is covered by device-level evidence.
- [ ] id: DR-003 | area: notification-events | phase: later | item: Replace remaining Android-framework edge adapters with pure command fixtures where unit tests still need framework-shaped data.
- [ ] id: DR-004 | area: app-registry | phase: later | item: Review package removal and reinstall handling without deleting existing notification history.
- [ ] id: DR-005 | area: ui-state | phase: later | item: Split grouped notification screen state mapping from Compose rendering while preserving Korean labels and paging behavior.

## Rules

- Keep notification storage append-only unless a later Seed explicitly changes it.
- Do not change the Room schema version for these deferred items without a dedicated schema task.
- Do not delete notification history when apps are removed.

## Notification Lifecycle Storage Rationale

PushRecorder currently stores notification lifecycle changes as an append-only
event log in the `notifications` table. A posted callback writes a `POSTED` row,
and the matching removed callback writes a later terminal row such as `REMOVED`.
This deliberately preserves the original NotificationListenerService event
stream instead of mutating the posted row in place.

The tradeoff is row volume: a notification that is both posted and removed uses
two durable rows, so a day with about 10,000 complete notification lifecycles can
produce about 20,000 notification rows before retention cleanup. Keeping both
rows preserves ordering, duplicate/repost behavior for the same notification
key, time-to-removal calculation, removal reason history, and compatibility with
existing grouped and lifecycle queries that infer the latest logical state from
the append-only sequence.

A future single-row lifecycle model could update the original posted row with
terminal fields such as removed time, reason, and duration. That would roughly
halve lifecycle row growth and simplify some active-state reads, but it would
trade away the current event-log shape, require a schema migration and query
rewrite, make duplicate/replayed listener events harder to audit, and need a
compatibility plan for already-recorded POSTED/REMOVED rows.
