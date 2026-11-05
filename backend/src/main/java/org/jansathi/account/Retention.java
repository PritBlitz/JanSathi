package org.jansathi.account;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jansathi.web.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The retention periods of docs/PRIVACY_AND_SECURITY.md §3, run every night. Every step is safe to
 * repeat, so two instances running it do no harm. One-time codes and sessions are cleaned hourly by
 * {@link Accounts#cleanUp()}.
 */
@Service
public class Retention {

	private static final Logger log = LoggerFactory.getLogger(Retention.class);
	static final int INACTIVE_MONTHS = 12;
	static final int HELPER_IDLE_DAYS = 90;

	public record Result(int helpersDeactivated, int accountsDeleted, int auditRowsDeleted, int sharesDeleted, int closedCasesDeleted) {}

	private final JdbcClient jdbc;
	private final Accounts accounts;
	private final ErrorReporter errors;
	private final MeterRegistry meters;

	public Retention(JdbcClient jdbc, Accounts accounts, ErrorReporter errors, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.errors = errors;
		this.meters = meters;
	}

	private int delete(String sql, OffsetDateTime cutoff) {
		return jdbc.sql(sql).param("t", cutoff).update();
	}

	public Result run(OffsetDateTime now) {
		int helpers = accounts.deactivateIdleHelpers(now.minusDays(HELPER_IDLE_DAYS));
		// Cascades to the household, conversation, shares, notes, reminders and audit log.
		int deleted = delete("DELETE FROM app_user WHERE COALESCE(last_seen_at, last_login_at, created_at) < :t", now.minusMonths(INACTIVE_MONTHS));
		// A day past the year: the database refuses to delete a row younger than a year, and its clock
		// may differ a little from ours.
		OffsetDateTime year = now.minusYears(1).minusDays(1);
		int audit = delete("DELETE FROM audit_log WHERE created_at < :t", year) + delete("DELETE FROM admin_audit WHERE created_at < :t", year);
		int shares = delete("DELETE FROM case_share WHERE expires_at < :t", year);
		int closed = delete("DELETE FROM grievance WHERE resolved_at < :t", year) + delete("DELETE FROM helper_report WHERE resolved_at < :t", year);
		delete("DELETE FROM reminder_sent WHERE sent_at < :t", year);
		return new Result(helpers, deleted, audit, shares, closed);
	}

	/** 03:30 in India. */
	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
	void nightly() {
		try {
			Result r = run(OffsetDateTime.now(ZoneOffset.UTC));
			meters.counter("jansathi.retention.accounts_deleted").increment(r.accountsDeleted());
			log.info("Retention: {} helpers deactivated, {} accounts, {} audit rows, {} shares, {} closed cases deleted",
					r.helpersDeactivated(), r.accountsDeleted(), r.auditRowsDeleted(), r.sharesDeleted(), r.closedCasesDeleted());
		} catch (RuntimeException e) {
			log.error("Retention job failed: {}", e.getClass().getName());
			errors.report(e, "job retention");
		}
	}
}
