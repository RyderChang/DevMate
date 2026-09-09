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
