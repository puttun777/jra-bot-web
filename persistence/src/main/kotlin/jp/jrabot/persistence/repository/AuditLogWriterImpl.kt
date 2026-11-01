package jp.jrabot.persistence.repository

import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.persistence.dao.AuditLogDao
import jp.jrabot.persistence.dao.AuditLogEntity
import jp.jrabot.persistence.mapping.toDbTime
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant

internal class AuditLogWriterImpl(
    private val dao: AuditLogDao,
    private val tx: TransactionOperations,
) : AuditLogWriter {
    override fun append(
        entry: AuditEntry,
        at: Instant,
    ) {
        tx.inTransaction {
            dao.insert(
                AuditLogEntity(
                    null,
                    entry.tenantId.value,
                    at.toDbTime(),
                    "USER",
                    entry.actor,
                    entry.action,
                    entry.targetType,
                    entry.targetId,
                    entry.beforeJson,
                    entry.afterJson,
                    entry.reason,
                    entry.requestId,
                ),
            )
        }
    }
}
