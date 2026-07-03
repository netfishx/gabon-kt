package com.gabon

import com.gabon.content.internal.feed.FeedOrchestrator
import com.gabon.wallet.internal.ledger.LedgerService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 验收④：协程边界——suspend 编排并发 fan-out 调用阻塞钱核/读服务，
 * 事务路径本身保持阻塞（LedgerService 非 suspend）。
 */
class CoroutineBoundaryTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var ledger: LedgerService

    @Autowired
    lateinit var feed: FeedOrchestrator

    @Test
    fun `suspend orchestration fans out over blocking services`() {
        runBlocking {
            val customer = 300L
            ledger.creditRecharge(customer, 250, "CR-3") // 阻塞 @Transactional 钱核

            val view = feed.assemble(customer) // suspend 编排 + 结构化并发

            assertThat(view.balance).isEqualTo(250)
            assertThat(view.hot).containsExactly(3, 1, 2)
            assertThat(view.seen).containsExactly(9)
        }
    }
}
