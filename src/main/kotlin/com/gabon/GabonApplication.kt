package com.gabon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class GabonApplication

fun main(args: Array<String>) {
    runApplication<GabonApplication>(*args)
}
