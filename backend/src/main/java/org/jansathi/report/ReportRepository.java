package org.jansathi.report;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The report moderation queue (table {@code report}, V1 migration). */
@Repository
public class ReportRepository {

	/** One report as stored. Holds no personal data. */
	public record Report(
			String id,
			Instant receivedAt,
			String scheme,
			String reason,
			String note,
			String lang,
			String bundleVersion,
			String status,
			Instant resolvedAt,
			String resolution) {}

	private final JdbcClient jdbc;

	public ReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Report r) {
		jdbc.sql("""
				INSERT INTO report (id, received_at, scheme, reason, note, lang, bundle_version, status)
				VALUES (:id, :receivedAt, :scheme, :reason, :note, :lang, :bundleVersion, 'open')
				""")
				.param("id", r.id())
				.param("receivedAt", OffsetDateTime.ofInstant(r.receivedAt(), ZoneOffset.UTC))
				.param("scheme", r.scheme())
				.param("reason", r.reason())
				.param("note", r.note())
				.param("lang", r.lang())
				.param("bundleVersion", r.bundleVersion())
				.update();
	}

	/** Newest first; {@code status} null lists every report. */
	public List<Report> list(String status, int limit) {
		String where = status == null ? "" : "WHERE status = :status ";
		var spec = jdbc.sql("SELECT * FROM report " + where + "ORDER BY received_at DESC LIMIT :limit").param("limit", limit);
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query((rs, i) -> new Report(
				rs.getString("id"),
				rs.getObject("received_at", OffsetDateTime.class).toInstant(),
				rs.getString("scheme"),
				rs.getString("reason"),
				rs.getString("note"),
				rs.getString("lang"),
				rs.getString("bundle_version"),
				rs.getString("status"),
				Optional.ofNullable(rs.getObject("resolved_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null),
				rs.getString("resolution"))).list();
	}

	/** Closes an open report. Returns false if there is no open report with this id. */
	public boolean resolve(String id, String status, String resolution) {
		return jdbc.sql("""
				UPDATE report SET status = :status, resolved_at = :now, resolution = :resolution
				WHERE id = :id AND status = 'open'
				""")
				.param("status", status)
				.param("now", OffsetDateTime.now(ZoneOffset.UTC))
				.param("resolution", resolution)
				.param("id", id)
				.update() == 1;
	}
}
