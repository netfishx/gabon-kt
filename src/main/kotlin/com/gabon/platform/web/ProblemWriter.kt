package com.gabon.platform.web

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

/** Servlet 层 problem+json 统一写出(过滤器/入口点/拒绝处理器共用);编码显式 UTF-8,不赖容器默认。 */
object ProblemWriter {
    fun write(
        response: HttpServletResponse,
        objectMapper: ObjectMapper,
        type: ProblemType,
    ) {
        response.status = type.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(type.toProblemDetail()))
    }
}
