/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.plugins

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.*
import kotlin.reflect.*

/**
 * A plugin that handles exceptions and status codes. Useful to configure default error pages.
 */
public class StatusPages private constructor(config: Configuration) {
    private val exceptions = HashMap(config.exceptions)
    private val statuses = HashMap(config.statuses)

    /**
     * Status pages plugin config
     */
    public class Configuration {
        /**
         * Exception handlers map by exception class
         */
        public val exceptions: MutableMap<KClass<*>, suspend PipelineContext<*, ApplicationCall>.(Throwable) -> Unit> =
            mutableMapOf()

        /**
         * Status handlers by status code
         */
        public val statuses: MutableMap<HttpStatusCode,
            suspend PipelineContext<*, ApplicationCall>.(HttpStatusCode) -> Unit> =
            mutableMapOf()

        /**
         * Register exception [handler] for exception type [T] and it's children
         */
        public inline fun <reified T : Throwable> exception(
            noinline handler: suspend PipelineContext<Unit, ApplicationCall>.(T) -> Unit
        ): Unit = exception(T::class, handler)

        /**
         * Register exception [handler] for exception class [klass] and it's children
         */
        public fun <T : Throwable> exception(
            klass: KClass<T>,
            handler: suspend PipelineContext<Unit, ApplicationCall>.(T) -> Unit
        ) {
            @Suppress("UNCHECKED_CAST")
            val cast = handler as suspend PipelineContext<*, ApplicationCall>.(Throwable) -> Unit

            exceptions[klass] = cast
        }

        /**
         * Register status [handler] for [status] code
         */
        public fun status(
            vararg status: HttpStatusCode,
            handler: suspend PipelineContext<*, ApplicationCall>.(HttpStatusCode) -> Unit
        ) {
            status.forEach {
                statuses[it] = handler
            }
        }
    }

    private suspend fun interceptResponse(context: PipelineContext<*, ApplicationCall>, message: Any) {
        val call = context.call
        if (call.attributes.contains(key)) return

        val status = when (message) {
            is OutgoingContent -> message.status
            is HttpStatusCode -> message
            else -> null
        } ?: return

        val handler = statuses[status] ?: return

        call.attributes.put(key, this@StatusPages)
        context.handler(status)
        finishIfResponseSent(context)
    }

    private fun finishIfResponseSent(context: PipelineContext<*, ApplicationCall>) {
        if (context.call.response.status() != null) {
            context.finish()
        }
    }

    private suspend fun interceptCall(context: PipelineContext<Unit, ApplicationCall>) {
        try {
            coroutineScope {
                context.proceed()
            }
        } catch (exception: Throwable) {
            val handler = findHandlerByValue(exception)
            if (handler != null && context.call.response.status() == null) {
                context.handler(exception)
                finishIfResponseSent(context)
            } else {
                throw exception
            }
        }
    }

    private fun findHandlerByValue(cause: Throwable): HandlerFunction? {
        val key = exceptions.keys.find { cause.instanceOf(it) } ?: return null
        return exceptions[key]
    }

    /**
     * Plugin installation object
     */
    public companion object Plugin : ApplicationPlugin<ApplicationCallPipeline, Configuration, StatusPages> {
        override val key: AttributeKey<StatusPages> = AttributeKey("Status Pages")

        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): StatusPages {
            val configuration = Configuration().apply(configure)
            val plugin = StatusPages(configuration)
            if (plugin.statuses.isNotEmpty()) {
                pipeline.sendPipeline.intercept(ApplicationSendPipeline.After) { message ->
                    plugin.interceptResponse(this, message)
                }
            }
            if (plugin.exceptions.isNotEmpty()) {
                pipeline.intercept(ApplicationCallPipeline.Monitoring) {
                    plugin.interceptCall(this)
                }
            }
            return plugin
        }
    }
}

private typealias HandlerFunction = suspend PipelineContext<Unit, ApplicationCall>.(Throwable) -> Unit
