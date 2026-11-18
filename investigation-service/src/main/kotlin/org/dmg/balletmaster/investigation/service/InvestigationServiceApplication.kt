package org.dmg.balletmaster.investigation.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(InvestigationProperties::class)
class InvestigationServiceApplication

fun main(args: Array<String>) {
    runApplication<InvestigationServiceApplication>(*args)
}
