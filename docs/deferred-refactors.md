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
