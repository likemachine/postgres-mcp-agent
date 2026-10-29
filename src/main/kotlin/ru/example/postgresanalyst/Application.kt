package ru.example.postgresanalyst

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PostgresAnalystApplication

fun main(args: Array<String>) {
    runApplication<PostgresAnalystApplication>(*args)
}
