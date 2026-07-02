package com.gabon.outbox

import com.gabon.jooq.tables.references.OUTBOX
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

const val STATUS_READY: Short = 0
const val STATUS_INFLIGHT: Short = 1

@Repository
class OutboxRepo(
    private val dsl: DSLContext,
) {
    fun enqueue(
        aggregate: String,
        eventType: Short,
        payloadJson: String,
    ) {
        dsl
            .insertInto(OUTBOX, OUTBOX.AGGREGATE, OUTBOX.EVENT_TYPE, OUTBOX.PAYLOAD)
            .values(aggregate, eventType, JSONB.valueOf(payloadJson))
            .execute()
    }

    /**
     * 原子领取：SELECT ... FOR UPDATE SKIP LOCKED 锁定 → 同事务内置租约。
     * 并发调用各自事务，SKIP LOCKED 保证领到的行不重叠（不重复派发）。
     * 领取范围含**过期租约重捡**：READY（到点）OR IN_FLIGHT 但 lease_until 已过
     * ——worker 领后崩溃不会让行永久卡在 IN_FLIGHT（见架构文档 C3 队列规范）。
     */
    @Transactional
    fun lease(
        batch: Int,
        leaseSeconds: Long,
    ): List<Long> {
        // 全程用 DB 时钟(now())，不混 JVM 时钟——worker 分布多机，队列可见性/租约必须以 DB 时间为准。
        val dbNow = DSL.currentOffsetDateTime()
        val leaseUntil = DSL.field("now() + (interval '1 second' * ?)", OffsetDateTime::class.java, leaseSeconds)
        val ready = OUTBOX.STATUS.eq(STATUS_READY).and(OUTBOX.NEXT_RUN_AT.le(dbNow))
        val expired = OUTBOX.STATUS.eq(STATUS_INFLIGHT).and(OUTBOX.LEASE_UNTIL.lt(dbNow))
        // 目标形态：一条 UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING
        return dsl
            .update(OUTBOX)
            .set(OUTBOX.STATUS, STATUS_INFLIGHT)
            .set(OUTBOX.LEASE_UNTIL, leaseUntil)
            .set(OUTBOX.ATTEMPTS, OUTBOX.ATTEMPTS.plus(1))
            .where(
                OUTBOX.ID.`in`(
                    dsl
                        .select(OUTBOX.ID)
                        .from(OUTBOX)
                        .where(ready.or(expired))
                        .orderBy(OUTBOX.NEXT_RUN_AT)
                        .limit(batch)
                        .forUpdate()
                        .skipLocked(),
                ),
            ).returningResult(OUTBOX.ID)
            .fetch()
            .mapNotNull { it.value1() }
    }
}
