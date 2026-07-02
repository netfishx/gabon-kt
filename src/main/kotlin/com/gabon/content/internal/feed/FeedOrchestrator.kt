package com.gabon.content.internal.feed

import com.gabon.wallet.api.WalletBalanceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

data class FeedView(
    val balance: Long,
    val hot: List<Long>,
    val seen: Set<Long>,
)

/**
 * 编排层：允许协程（结构化并发 + 超时 + 取消），仅做多源 fan-out 合并。
 * 落库/事务动作回到阻塞 @Transactional service 边界（wallet.api）。
 * 见架构文档 B5.1：suspend 编排 → 阻塞服务；本层禁 @Transactional。
 */
@Component
class FeedOrchestrator(
    private val ledger: WalletBalanceApi,
) {
    suspend fun assemble(customerId: Long): FeedView =
        coroutineScope {
            withTimeout(2.seconds) {
                val balance = async(Dispatchers.IO) { ledger.balanceOf(customerId) } // 阻塞读放 IO 调度器
                val hot = async(Dispatchers.IO) { hotScores() }
                val seen = async(Dispatchers.IO) { seenVideos(customerId) }
                FeedView(balance.await(), hot.await(), seen.await())
            }
        }

    @Suppress("MagicNumber") // 占位假数据；真实场景查 Valkey ZSET
    private fun hotScores(): List<Long> = listOf(3, 1, 2)

    @Suppress("MagicNumber", "UnusedParameter") // 占位 stub；真实场景按 customerId 查已看集合
    private fun seenVideos(customerId: Long): Set<Long> = setOf(9)
}
