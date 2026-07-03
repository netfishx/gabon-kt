package com.gabon

import com.gabon.platform.web.ProblemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class ProblemTypeTest {
    @Test
    fun `renders stable uri title and status`() {
        val pd = ProblemType.INVALID_CREDENTIALS.toProblemDetail()
        assertThat(pd.type).isEqualTo(URI.create("/problems/invalid-credentials"))
        assertThat(pd.title).isEqualTo("Invalid credentials")
        assertThat(pd.status).isEqualTo(401)
        assertThat(pd.detail).isNull()
    }
}
