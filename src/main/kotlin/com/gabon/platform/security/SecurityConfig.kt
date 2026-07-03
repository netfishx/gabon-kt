package com.gabon.platform.security

import com.gabon.platform.web.ProblemType
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /** platform 自己贡献 actuator 健康探针公开路由。 */
    @Bean
    fun platformPublicRoutes(): PublicRoutesContributor = PublicRoutesContributor { listOf("/actuator/health", "/actuator/health/**") }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtFilter: JwtAuthFilter,
        contributors: List<PublicRoutesContributor>,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { registry ->
                // 逐条登记(不用 spread):contributor 是默认拒绝的唯一放行通道
                contributors.flatMap { it.publicRoutes() }.forEach { route ->
                    registry.requestMatchers(route).permitAll()
                }
                registry.requestMatchers("/v1/admin/**").hasRole("ADMIN")
                registry.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, objectMapper, ProblemType.UNAUTHENTICATED)
                }
                it.accessDeniedHandler { _, response, _ ->
                    writeProblem(response, objectMapper, ProblemType.FORBIDDEN)
                }
            }.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private fun writeProblem(
        response: HttpServletResponse,
        objectMapper: ObjectMapper,
        type: ProblemType,
    ) {
        response.status = type.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(type.toProblemDetail()))
    }
}
