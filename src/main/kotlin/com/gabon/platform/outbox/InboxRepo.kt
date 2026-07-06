package com.gabon.platform.outbox

import com.gabon.jooq.tables.references.INBOX
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

/**
 * 入站回调去重(spec C2.5/§4.3-3):Inbox 表归 platform(规则 6),业务域一律经此收口。
 * 必须在调用方业务事务内调用——inbox 记录与业务效果同生共死,拆开会吞掉渠道重试(spec §4.3-5)。
 * source 是全局命名空间:域基数 + channel(recharge 1000 / withdraw 2000,spec §4.3-3)。
 */
@Repository
class InboxRepo(
    private val dsl: DSLContext,
) {
    /** true=首见;false=同 (source, external_id) 已处理(重复回调,调用方短路 ack)。 */
    fun tryRecord(
        source: Short,
        externalId: String,
    ): Boolean =
        dsl
            .insertInto(INBOX, INBOX.SOURCE, INBOX.EXTERNAL_ID)
            .values(source, externalId)
            .onConflictDoNothing()
            .execute() == 1
}
