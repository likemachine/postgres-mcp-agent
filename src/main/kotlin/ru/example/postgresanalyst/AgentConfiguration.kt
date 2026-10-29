package ru.example.postgresanalyst

import org.eclipse.lmos.arc.agents.ChatAgent
import org.eclipse.lmos.arc.agents.dsl.AgentDefinition
import org.eclipse.lmos.arc.agents.dsl.BeanProvider
import org.eclipse.lmos.arc.agents.dsl.ChatAgentFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {

    @Bean
    fun postgresAnalyst(beanProvider: BeanProvider): ChatAgent {
        val definition = AgentDefinition().apply {
            name = "postgres-analyst"
            description = "Аналитик PostgreSQL с доступом к MCP-инструментам."

            model {
                "qwen2.5-coder:7b"
            }

            tools = listOf(
                "get_db_health",
                "list_tables",
                "describe_table",
                "get_table_stats",
                "run_select_query",
                "explain_query"
            )

            prompt {
                """
                Ты — аналитик PostgreSQL. Отвечай на русском языке.

                Правила:
                1. Для данных из базы вызывай инструмент, ответив ТОЛЬКО JSON: {"name":"<инструмент>","arguments":{...}}, без текста и Markdown. Не называй таблицы и значения, пока не получил результат инструмента.
                Query plans: for any question about EXPLAIN, the execution plan, how a query is executed or performs, or whether a query uses an index, ALWAYS call explain_query with the exact SQL from the user. Never guess a plan from describe_table or get_table_stats.
                2. Перед составлением SQL изучи список таблиц и структуру
                   нужных таблиц.
                3. Не выдумывай таблицы, колонки и результаты запросов.
                4. Используй только разрешённые read-only SELECT-запросы.
                5. Не изменяй данные и структуру базы.
                6. При ошибке инструмента честно сообщи об ошибке.
                7. После получения данных объясни результат и покажи SQL,
                   если выполнялся SQL-запрос.
                8. Если вопрос неоднозначен, задай уточняющий вопрос.
                """.trimIndent()
            }
        }

        return ChatAgentFactory(beanProvider).createAgent(definition)
    }
}
