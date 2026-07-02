package com.gabon

import com.gabon.outbox.OutboxRepo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** 验收④：outbox SKIP LOCKED 原子领取——并发领取不重叠（不重复派发） */
class OutboxLeaseTest : AbstractPgTest() {
    @Autowired
    lateinit var outbox: OutboxRepo

    @Test
    fun `concurrent lease does not double-dispatch`() {
        repeat(10) { outbox.enqueue("agg", 1, """{"i":$it}""") }

        val a = ArrayList<Long>()
        val b = ArrayList<Long>()
        val start = CountDownLatch(1)
        val t1 =
            thread {
                start.await()
                a.addAll(outbox.lease(6, 60))
            }
        val t2 =
            thread {
                start.await()
                b.addAll(outbox.lease(6, 60))
            }
        start.countDown()
        t1.join()
        t2.join()

        assertThat(a.intersect(b.toSet())).isEmpty() // 无重叠
        assertThat((a + b).toSet().size).isEqualTo((a + b).size) // 无重复
        assertThat((a + b).size).isEqualTo(10) // 10 行全部领出：不丢、不重(收紧前是 ≤10，会在 0 行时误过)
    }

    /** 过期租约重捡：worker 领后崩溃（租约到期）→ 下一轮 lease 应重新领到该行，不永久卡死 */
    @Test
    fun `expired in-flight lease is reclaimed`() {
        outbox.enqueue("agg", 1, """{"i":0}""")
        val first = outbox.lease(10, 60) // 领取 → IN_FLIGHT，lease_until = now+60s
        assertThat(first).hasSize(1)

        // 模拟 worker 崩溃 + 租约到期：把 lease_until 拨到过去
        dsl.execute("update outbox set lease_until = now() - interval '1 second' where id = ?", first[0])

        val second = outbox.lease(10, 60) // 过期租约应被重捡
        assertThat(second).containsExactly(first[0])
    }
}
