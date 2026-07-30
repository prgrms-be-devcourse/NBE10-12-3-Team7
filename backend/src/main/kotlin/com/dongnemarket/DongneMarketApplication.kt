package com.dongnemarket

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class DongneMarketApplication

fun main(args: Array<String>) {
    runApplication<DongneMarketApplication>(*args)
}
