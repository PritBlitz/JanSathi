#!/bin/sh
# Runs once, when the database volume is first created (docker-entrypoint-initdb.d). Creates the role
# the API connects as: it owns nothing, so it can't alter the audit logs or their triggers. Grants come
# from the migration db/vendor/postgresql/V4_1__audit_insert_only.sql, which runs as the owner.
# On an existing database, see docs/OPERATIONS.md section 3.
set -eu
: "${POSTGRES_APP_PASSWORD:?set POSTGRES_APP_PASSWORD}"
psql -v ON_ERROR_STOP=1 -v pw="$POSTGRES_APP_PASSWORD" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE jansathi_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw';
SQL
