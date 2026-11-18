package gg.grounds.notifications.safety

import gg.grounds.notifications.moderation.ModerationEnvironment
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal enum class Admission {
    ACCEPTED,
    CONFLICT,
    EXPIRED,
    FULL,
}

internal data class SafetyLease(val env: ModerationEnvironment, val event: UUID, val token: UUID)

@ApplicationScoped
class SafetyStore(private val source: DataSource) {
    private fun <T> tx(block: (Connection) -> T): T =
        source.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { s ->
                    s.execute("SET LOCAL lock_timeout='1s'")
                    s.execute("SET LOCAL statement_timeout='2s'")
                }
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }

    private fun now(c: Connection): Instant =
        c.createStatement().use { s ->
            s.executeQuery("SELECT clock_timestamp()").use { r ->
                r.next()
                r.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }

    internal fun admit(e: SafetyEnvelope): Admission = tx { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(199891,?)").use { s ->
            s.setInt(1, e.environment.ordinal)
            s.execute()
        }
        val time = now(c)
        if (
            !e.occurredAt.plusSeconds(86400).isAfter(time) ||
                e.occurredAt.isAfter(time.plusSeconds(5))
        )
            return@tx Admission.EXPIRED
        c.prepareStatement(
                "SELECT event_id,signal_ref,occurred_at_utc FROM notification_safety_signal WHERE environment=? AND (event_id=? OR signal_ref=?) FOR UPDATE"
            )
            .use { s ->
                s.setString(1, e.environment.wireValue)
                s.setObject(2, e.eventId)
                s.setObject(3, e.signalRef)
                s.executeQuery().use { r ->
                    if (r.next())
                        return@tx if (
                            r.getObject(1) == e.eventId &&
                                r.getObject(2) == e.signalRef &&
                                r.getString(3) == e.occurredAt.toString()
                        )
                            Admission.ACCEPTED
                        else Admission.CONFLICT
                }
            }
        c.prepareStatement(
                "INSERT INTO notification_safety_signal(environment,event_id,signal_ref,occurred_at_utc,expires_at) SELECT ?,?,?,?,? WHERE (SELECT count(*) FROM notification_safety_signal WHERE environment=?)<1000"
            )
            .use { s ->
                s.setString(1, e.environment.wireValue)
                s.setObject(2, e.eventId)
                s.setObject(3, e.signalRef)
                s.setString(4, e.occurredAt.toString())
                s.setObject(
                    5,
                    e.occurredAt
                        .plusSeconds(86400)
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        .atOffset(ZoneOffset.UTC),
                )
                s.setString(6, e.environment.wireValue)
                if (s.executeUpdate() == 1) Admission.ACCEPTED else Admission.FULL
            }
    }

    internal fun purge(): Int = tx { c ->
        c.createStatement().use {
            it.executeUpdate(
                """WITH expired AS (
       SELECT environment,event_id FROM notification_safety_signal WHERE expires_at<=clock_timestamp()
       ORDER BY expires_at LIMIT 32 FOR UPDATE SKIP LOCKED)
       DELETE FROM notification_safety_signal s USING expired x WHERE (s.environment,s.event_id)=(x.environment,x.event_id)"""
            )
        }
    }

    internal fun claim(enabled: Collection<ModerationEnvironment>): SafetyLease? {
        if (enabled.isEmpty()) return null
        return tx { c ->
            val token = UUID.randomUUID()
            c.prepareStatement(
                    """WITH candidate AS (
              SELECT environment,event_id FROM notification_safety_signal WHERE
               ((environment='stage' AND ?) OR (environment='prod' AND ?)) AND expires_at>clock_timestamp() AND
               ((state='PENDING' AND available_at<=clock_timestamp()) OR (state='LEASED' AND lease_until<=clock_timestamp()))
               ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED)
              UPDATE notification_safety_signal s SET state='LEASED',lease_token=?,lease_until=clock_timestamp()+interval '15 seconds',attempts=attempts+1
              FROM candidate x WHERE (s.environment,s.event_id)=(x.environment,x.event_id) RETURNING s.environment,s.event_id"""
                )
                .use { s ->
                    s.setBoolean(1, ModerationEnvironment.STAGE in enabled)
                    s.setBoolean(2, ModerationEnvironment.PROD in enabled)
                    s.setObject(3, token)
                    s.executeQuery().use { r ->
                        if (!r.next()) null
                        else
                            SafetyLease(
                                ModerationEnvironment.fromWire(r.getString(1)),
                                r.getObject(2, UUID::class.java),
                                token,
                            )
                    }
                }
        }
    }

    internal fun retry(l: SafetyLease) = tx { c ->
        c.prepareStatement(
                """UPDATE notification_safety_signal SET state='PENDING',lease_token=NULL,lease_until=NULL,
        available_at=clock_timestamp()+least(60,attempts*5)*interval '1 second' WHERE environment=? AND event_id=? AND state='LEASED' AND lease_token=?
        AND lease_until>clock_timestamp() AND expires_at>clock_timestamp()"""
            )
            .use { s ->
                s.setString(1, l.env.wireValue)
                s.setObject(2, l.event)
                s.setObject(3, l.token)
                s.executeUpdate()
            }
    }

    internal fun complete(l: SafetyLease, a: SafetyAudience): Boolean {
        if (a.recipients.size > 1000 || !a.fresh()) return false
        return tx { c ->
            c.prepareStatement(
                    "SELECT 1 FROM notification_safety_signal WHERE environment=? AND event_id=? AND state='LEASED' AND lease_token=? AND lease_until>clock_timestamp() AND expires_at>clock_timestamp() FOR UPDATE"
                )
                .use { s ->
                    s.setString(1, l.env.wireValue)
                    s.setObject(2, l.event)
                    s.setObject(3, l.token)
                    s.executeQuery().use { if (!it.next()) return@tx false }
                }
            val id = UUID.randomUUID()
            c.prepareStatement(
                    "INSERT INTO notification_safety_inbox(id,environment,event_id) VALUES(?,?,?)"
                )
                .use { s ->
                    s.setObject(1, id)
                    s.setString(2, l.env.wireValue)
                    s.setObject(3, l.event)
                    s.executeUpdate()
                }
            c.prepareStatement(
                    "INSERT INTO notification_safety_recipient(notification_id,user_id) VALUES(?,?)"
                )
                .use { s ->
                    for (user in a.recipients) {
                        s.setObject(1, id)
                        s.setObject(2, user)
                        s.addBatch()
                    }
                    s.executeBatch()
                }
            // Throw to roll back the complete audience and notification when freshness or lease
            // expires mid-write.
            c.prepareStatement(
                    """UPDATE notification_safety_signal SET state='READY',lease_token=NULL,lease_until=NULL
                WHERE environment=? AND event_id=? AND lease_token=? AND lease_until>clock_timestamp() AND expires_at>clock_timestamp()
                AND clock_timestamp() < ?::timestamptz+interval '5 seconds'"""
                )
                .use { s ->
                    s.setString(1, l.env.wireValue)
                    s.setObject(2, l.event)
                    s.setObject(3, l.token)
                    s.setObject(
                        4,
                        a.resolvedAt
                            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                            .atOffset(ZoneOffset.UTC),
                    )
                    check(s.executeUpdate() == 1)
                }
            true
        }
    }

    private val visible =
        """SELECT n.id,n.created_at,r.read_at,s.signal_ref FROM notification_safety_inbox n
        JOIN notification_safety_signal s USING(environment,event_id) JOIN notification_safety_recipient r ON r.notification_id=n.id
        WHERE n.environment=? AND r.user_id=? AND s.state='READY' AND s.expires_at>clock_timestamp()"""

    private fun item(r: ResultSet, env: ModerationEnvironment) =
        SafetyInboxItem(
            r.getObject("id", UUID::class.java),
            r.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            r.getObject("read_at", OffsetDateTime::class.java)?.toInstant(),
            OpenSafetySignal(env, r.getObject("signal_ref", UUID::class.java)),
        )

    internal fun page(
        env: ModerationEnvironment,
        user: UUID,
        limit: Int,
        cursor: UUID?,
    ): SafetyInboxPage? = tx { c ->
        c.prepareStatement(
                """WITH visible AS ($visible), input(cursor) AS (VALUES (?::uuid)), anchor AS
            (SELECT v.created_at,v.id FROM visible v,input i WHERE v.id=i.cursor), page AS
            (SELECT v.* FROM visible v,input i WHERE i.cursor IS NULL OR (v.created_at,v.id)<(SELECT created_at,id FROM anchor)
             ORDER BY created_at DESC,id DESC LIMIT ?)
            SELECT p.*,i.cursor IS NULL OR EXISTS(SELECT FROM anchor) AS valid FROM input i LEFT JOIN page p ON true ORDER BY p.created_at DESC,p.id DESC"""
            )
            .use { s ->
                s.setString(1, env.wireValue)
                s.setObject(2, user)
                s.setObject(3, cursor)
                s.setInt(4, limit + 1)
                s.executeQuery().use { r ->
                    check(r.next())
                    if (!r.getBoolean("valid")) return@tx null
                    val rows = mutableListOf<SafetyInboxItem>()
                    do {
                        if (r.getObject("id") != null) rows.add(item(r, env))
                    } while (r.next())
                    SafetyInboxPage(
                        rows.take(limit),
                        if (rows.size > limit) rows[limit - 1].id else null,
                    )
                }
            }
    }

    internal fun detail(env: ModerationEnvironment, user: UUID, id: UUID): SafetyInboxItem? =
        tx { c ->
            c.prepareStatement("$visible AND n.id=?").use { s ->
                s.setString(1, env.wireValue)
                s.setObject(2, user)
                s.setObject(3, id)
                s.executeQuery().use { r -> if (r.next()) item(r, env) else null }
            }
        }

    internal fun unread(env: ModerationEnvironment, user: UUID): SafetyUnreadCount = tx { c ->
        c.prepareStatement("SELECT count(*) FROM ($visible AND r.read_at IS NULL) eligible").use { s
            ->
            s.setString(1, env.wireValue)
            s.setObject(2, user)
            s.executeQuery().use { r ->
                r.next()
                SafetyUnreadCount(r.getLong(1))
            }
        }
    }

    internal fun mark(
        env: ModerationEnvironment,
        user: UUID,
        id: UUID,
        read: Boolean,
        a: SafetyAudience,
    ): Boolean = tx { c ->
        c.prepareStatement(
                "SELECT r.notification_id FROM notification_safety_recipient r WHERE r.notification_id=? AND r.user_id=? FOR UPDATE"
            )
            .use { s ->
                s.setObject(1, id)
                s.setObject(2, user)
                s.executeQuery().use { r -> if (!r.next()) return@tx false }
            }
        c.prepareStatement(
                """UPDATE notification_safety_recipient target SET read_at=CASE WHEN ? THEN coalesce(target.read_at,clock_timestamp()) ELSE NULL END
            WHERE target.notification_id=? AND target.user_id=? AND EXISTS ($visible AND n.id=?) AND clock_timestamp() < ?::timestamptz+interval '5 seconds'"""
            )
            .use { s ->
                s.setBoolean(1, read)
                s.setObject(2, id)
                s.setObject(3, user)
                s.setString(4, env.wireValue)
                s.setObject(5, user)
                s.setObject(6, id)
                s.setObject(
                    7,
                    a.resolvedAt
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        .atOffset(ZoneOffset.UTC),
                )
                s.executeUpdate() == 1
            }
    }
}
