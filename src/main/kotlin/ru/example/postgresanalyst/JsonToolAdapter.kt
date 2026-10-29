package ru.example.postgresanalyst

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.eclipse.lmos.arc.agents.ArcException
import org.eclipse.lmos.arc.agents.conversation.AssistantMessage
import org.eclipse.lmos.arc.agents.conversation.ConversationMessage
import org.eclipse.lmos.arc.agents.conversation.UserMessage
import org.eclipse.lmos.arc.agents.functions.LLMFunction
import org.eclipse.lmos.arc.agents.llm.ChatCompleter
import org.eclipse.lmos.arc.agents.llm.ChatCompleterProvider
import org.eclipse.lmos.arc.agents.llm.ChatCompletionSettings
import org.eclipse.lmos.arc.core.Failure
import org.eclipse.lmos.arc.core.Result
import org.eclipse.lmos.arc.core.Success
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component

@Component
class JsonToolAdapter : BeanPostProcessor {

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is ChatCompleterProvider) return bean

        return Proxy.newProxyInstance(
            ChatCompleterProvider::class.java.classLoader,
            arrayOf(ChatCompleterProvider::class.java)
        ) { _, method, args ->
            val value = try {
                method.invoke(bean, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

            if (
                method.name == "provideByModel" &&
                args?.firstOrNull() == "qwen2.5-coder:7b" &&
                value is ChatCompleter
            ) {
                JsonToolCompleter(value)
            } else {
                value
            }
        }
    }
}

private class JsonToolCompleter(
    private val delegate: ChatCompleter
) : ChatCompleter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lenient = ObjectMapper()
    private val mapper = ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    override suspend fun complete(
        messages: List<ConversationMessage>,
        functions: List<LLMFunction>?,
        settings: ChatCompletionSettings?
    ): Result<AssistantMessage, ArcException> {
        val available = functions.orEmpty()
        val conversation = messages.toMutableList()
        val used = mutableListOf<String>()
        var step = 0

        while (true) {
            val result = delegate.complete(conversation, functions, settings)
            if (result !is Success) return result

            val message = result.value
            if (!message.toolCalls.isNullOrEmpty()) return result

            val request = parseRequest(message.content)
            if (request == null) {
                val clean = message.content.lines()
                    .filterNot { it.contains("MCP:") || it.contains("MCP calls") }
                    .joinToString("\n").trim()
                val report = if (used.isEmpty()) {
                    "none, no tools were called"
                } else {
                    used.joinToString("; ")
                }
                return Success(
                    message.copy(
                        content = clean +
                            "\n\n[MCP calls verified by server: $report]"
                    )
                )
            }

            step++
            if (step > MAX_STEPS) {
                log.warn("MCP_STEP_LIMIT_REACHED")
                return Success(
                    message.copy(
                        content = "Достигнут лимит шагов ($MAX_STEPS). " +
                            "Вызовы MCP: " + used.joinToString("; "),
                        toolCalls = emptyList()
                    )
                )
            }

            val name = request.first
            val args = request.second
            val observation = execute(name, args, available, used)

            conversation.add(message.copy(toolCalls = emptyList()))
            conversation.add(
                UserMessage(
                    "Результат инструмента $name:\n$observation\n\n" +
                        "Используй только эти данные, не выдумывай значения. " +
                        "Если нужен ещё один инструмент, ответь только JSON " +
                        "{\"name\":\"...\",\"arguments\":{...}}. " +
                        "Иначе дай итоговый ответ на русском языке."
                )
            )
        }
    }

    private suspend fun execute(
        name: String,
        args: JsonNode,
        available: List<LLMFunction>,
        used: MutableList<String>
    ): String {
        val function = available.firstOrNull { it.name == name }
            ?: return "ERROR: unknown tool '$name'. Available: " +
                available.joinToString { it.name }

        val problem = validate(function, args)
        if (problem != null) return "ERROR: invalid arguments: $problem"

        @Suppress("UNCHECKED_CAST")
        val map = mapper.convertValue(args, Map::class.java) as Map<String, Any?>

        log.info("MCP_EXECUTE_START: {} {}", name, args)
        val text = when (val r = function.execute(map)) {
            is Success -> {
                log.info("MCP_EXECUTE_OK: {}", name)
                log.info("MCP_RESULT: {} {}", name, r.value.take(2000).lines().joinToString(" "))
                used.add("$name($args)")
                r.value
            }
            is Failure<*> -> {
                log.warn("MCP_EXECUTE_FAILED: {} {}", name, r.reason.message)
                "ERROR: ${r.reason.message}"
            }
            else -> "ERROR: unexpected result"
        }
        return if (text.length > MAX_RESULT_CHARS) {
            text.take(MAX_RESULT_CHARS) + "\n[результат обрезан]"
        } else {
            text
        }
    }

    private fun parseRequest(content: String): Pair<String, JsonNode>? {
        val start = Regex("\\{\\s*\"name\"").find(content) ?: return null
        val node: JsonNode = runCatching {
            lenient.createParser(content.substring(start.range.first)).use {
                lenient.readTree<JsonNode>(it)
            }
        }.getOrNull() ?: return null
        if (!node.isObject) return null
        if (node.fieldNames().asSequence().toSet() != setOf("name", "arguments")) {
            return null
        }
        val name = node.get("name")
        val args = node.get("arguments")
        if (!name.isTextual || !args.isObject) return null
        return name.asText() to args
    }

    private fun validate(function: LLMFunction, args: JsonNode): String? {
        val schema = function.parameters
        val props = schema.properties
        val names = args.fieldNames().asSequence().toList()

        names.firstOrNull { it !in props.keys }?.let {
            return "unknown argument '$it'"
        }
        schema.required.orEmpty().firstOrNull { !args.has(it) }?.let {
            return "missing required argument '$it'"
        }
        for (n in names) {
            val v = args.get(n)
            val type = props[n]?.type
            val ok = when (type) {
                "string" -> v.isTextual
                "integer" -> v.isIntegralNumber
                "number" -> v.isNumber
                "boolean" -> v.isBoolean
                "array" -> v.isArray
                "object" -> v.isObject
                else -> true
            }
            if (!ok) return "argument '$n' must be $type"
        }
        return null
    }

    companion object {
        private const val MAX_STEPS = 4
        private const val MAX_RESULT_CHARS = 8000
    }
}
