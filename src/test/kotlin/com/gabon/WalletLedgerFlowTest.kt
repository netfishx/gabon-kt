package com.gabon

import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.wallet.internal.ledger.LedgerService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** 钱核写方法验收(spec §3):守卫、并发、五方法幂等、不变量恒成立。 */
class WalletLedgerFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var ledger: LedgerService

    @Test
    fun `freeze then settle moves funds through frozen to clearing`() {
        ledger.creditRecharge("CR-10", 10L, 1000)
        assertThat(ledger.freezeForWithdraw("W-10", 10L, 400)).isTrue()
        assertThat(ledger.balanceOf(10L)).isEqualTo(600)
        assertThat(ledger.frozenOf(10L)).isEqualTo(400)
        assertThat(ledger.settleWithdraw("W-10", 10L, 400)).isTrue()
        assertThat(ledger.frozenOf(10L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `freeze then release returns funds intact`() {
        ledger.creditRecharge("CR-11", 11L, 1000)
        ledger.freezeForWithdraw("W-11", 11L, 400)
        assertThat(ledger.releaseFrozen("W-11", 11L, 400)).isTrue()
        assertThat(ledger.balanceOf(11L)).isEqualTo(1000)
        assertThat(ledger.frozenOf(11L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `insufficient balance freeze throws and leaves no trace`() {
        ledger.creditRecharge("CR-12", 12L, 100)
        assertThatThrownBy { ledger.freezeForWithdraw("W-12", 12L, 500) }
            .isInstanceOfSatisfying(ProblemException::class.java) {
                assertThat(it.type).isEqualTo(ProblemType.INSUFFICIENT_BALANCE)
            }
        assertThat(ledger.balanceOf(12L)).isEqualTo(100)
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1) // 失败冻结的 txn 头随回滚消失
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `concurrent freezes with funds for one let exactly one win`() {
        ledger.creditRecharge("CR-13", 13L, 500)
        val outcomes = arrayOfNulls<Result<Boolean>>(2)
        val start = CountDownLatch(1)
        val threads =
            (0..1).map { i ->
                thread {
                    start.await()
                    outcomes[i] = runCatching { ledger.freezeForWithdraw("W-13-$i", 13L, 400) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        val (won, lost) = outcomes.map { it!! }.partition { it.isSuccess }
        assertThat(won).hasSize(1)
        assertThat((lost.single().exceptionOrNull() as ProblemException).type)
            .isEqualTo(ProblemType.INSUFFICIENT_BALANCE)
        assertThat(ledger.balanceOf(13L)).isEqualTo(100)
        assertThat(ledger.frozenOf(13L)).isEqualTo(400)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `concurrent credits with one biz_no post exactly once`() {
        val outcomes = arrayOfNulls<Result<Boolean>>(2)
        val start = CountDownLatch(1)
        val threads =
            (0..1).map { i ->
                thread {
                    start.await()
                    outcomes[i] = runCatching { ledger.creditRecharge("CR-14", 14L, 300) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        // 败方 onConflictDoNothing 命中 0 行 → false;不允许异常路径
        assertThat(outcomes.map { it!!.getOrThrow() }.sorted()).containsExactly(false, true)
        assertThat(ledger.balanceOf(14L)).isEqualTo(300)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `grant reward funds customer from platform equity`() {
        assertThat(ledger.grantReward("TASK-1", 15L, 50)).isTrue()
        assertThat(ledger.balanceOf(15L)).isEqualTo(50) // platform_equity 转负(非用户账户无非负约束)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `every write method replays as a no-op`() {
        ledger.creditRecharge("CR-16", 16L, 1000)
        ledger.freezeForWithdraw("W-16", 16L, 400)
        ledger.settleWithdraw("W-16", 16L, 100)
        ledger.releaseFrozen("W-16", 16L, 300) // 同 withdrawNo 三 biz_type 独立幂等空间
        ledger.grantReward("R-16", 16L, 10)

        assertThat(ledger.creditRecharge("CR-16", 16L, 1000)).isFalse()
        assertThat(ledger.freezeForWithdraw("W-16", 16L, 400)).isFalse()
        assertThat(ledger.settleWithdraw("W-16", 16L, 100)).isFalse()
        assertThat(ledger.releaseFrozen("W-16", 16L, 300)).isFalse()
        assertThat(ledger.grantReward("R-16", 16L, 10)).isFalse()
        // 1000 − 400(freeze) + 300(release) + 10(reward) = 910;settle 只动 frozen
        assertThat(ledger.balanceOf(16L)).isEqualTo(910)
        assertThat(ledger.frozenOf(16L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }
}
