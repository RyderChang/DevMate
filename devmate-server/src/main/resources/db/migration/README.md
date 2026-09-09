# Database migrations

Future Flyway migrations belong in this directory and use the naming convention
`V<version>__<description>.sql`, for example `V1__create_project_table.sql`.

Never modify a migration after it has been merged or executed in a shared environment.
DEV-004 establishes only this convention; Flyway and schema migrations are not enabled yet.
