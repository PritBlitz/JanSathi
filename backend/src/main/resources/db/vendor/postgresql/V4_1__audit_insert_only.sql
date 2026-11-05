-- PostgreSQL only (spring.flyway.locations adds db/vendor/{vendor}). PRIVACY §4.4: the audit logs are
-- insert-only. Enforced twice:
--   1. Triggers, which hold for every role that does not disable them, the table owner included.
--   2. The API's own role, jansathi_app, has no UPDATE or TRUNCATE on them and can't alter triggers,
--      because it does not own the tables. Flyway runs as the owner; the API does not.
-- A row may be deleted in only two ways:
--   - by the cascade from deleting the person (the right to erasure), seen as a nested trigger;
--   - once it is more than a year old (the retention period, PRIVACY §3).

CREATE FUNCTION audit_insert_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 OR OLD.created_at < now() - interval '1 year' THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION '% on % is not allowed: audit logs are insert-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER audit_log_insert_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_insert_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_insert_only();

CREATE TRIGGER admin_audit_insert_only BEFORE UPDATE OR DELETE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION audit_insert_only();
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON admin_audit
    FOR EACH STATEMENT EXECUTE FUNCTION audit_insert_only();

-- deploy/postgres/10-app-role.sh creates the role on a new database. Without it (a single-role
-- install) the triggers still hold. docs/OPERATIONS.md §3 has the steps for an existing database.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jansathi_app') THEN
        GRANT USAGE ON SCHEMA public TO jansathi_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jansathi_app;
        REVOKE ALL ON flyway_schema_history FROM jansathi_app;
        REVOKE UPDATE ON audit_log, admin_audit FROM jansathi_app;
        -- Tables from later migrations, created by the same owner.
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jansathi_app;
    END IF;
END;
$$;
