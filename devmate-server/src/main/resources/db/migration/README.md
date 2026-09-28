# Database migrations

Flyway runs the versioned SQL files in this directory in ascending version order when the
application starts. Name files `V<version>__<description>.sql`, for example
`V2__create_project_table.sql`. Descriptions use lowercase snake case.

Once a migration has been merged or run in a shared environment, it is immutable. Correct it
with a new, higher version rather than editing, deleting, or reordering it. Flyway validation
stops application startup when an applied migration no longer matches its checksum.

Migrations should be forward-compatible and include an explicit rollback plan in the change's
pull request. Production rollback normally restores compatible application code or applies a
new corrective migration; Flyway `clean` is disabled and must never be used for rollback.

`V1__baseline.sql` is the infrastructure baseline. It deliberately creates no application table.
`V5__create_conversations_and_ai_invocations.sql` adds application-managed conversations,
visible messages, generation leases, and auditable AI invocation metadata.

`V6__create_knowledge_document_storage.sql` adds document metadata,
per-project capacity reservations, and UUID/fingerprint mappings with 24-hour terminal retention.
Composite foreign keys enforce user/project ownership. Check constraints reject unknown states,
invalid digests, negative bytes and out-of-range counters. Indexes serve owner/project lists,
due recovery, project cleanup and terminal expiry. Original bytes and credentials are never stored here.

Remote PUT/DELETE runs outside transactions; project locks, leases and conditional state/version
updates protect reservations and cleanup. Capacity is released only when cleanup and the terminal
request mapping commit together. No object deletion is implemented as a database cascade.
V1–V5 remain unchanged. V6 is verified from an empty digest-pinned MySQL 8.4.6 container.

Rollback disables new storage operations while retaining pending records and private objects.
Do not drop these tables, clear the bucket or edit an applied migration. Correct shared schemas
with a new migration and resume cleanup after compatible application recovery. Application rollback
cannot restore physically deleted content; backup retention is an operator policy.

The latest migration is `V7__create_document_processing.sql`. It adds processing generations,
bounded UTF-8 chunks, request replay retention and project chunk capacity. Composite foreign keys,
position/byte checks, generation/ordinal uniqueness and a unique active document projection protect
ownership and publication. The document stores its explicit active generation reference.
Reservations and staging bytes share one capacity charge. Retirement and deletion retain the parent
until all derived content and reservations are cleaned. Original V1–V6 files remain immutable.
Disable processing for rollback, retain V7 and use application code that understands this cleanup
handoff. A pre-V7 application cannot safely perform the new parent cleanup.
