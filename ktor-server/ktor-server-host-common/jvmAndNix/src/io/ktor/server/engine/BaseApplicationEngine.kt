/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.engine.internal.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.concurrent.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*

/**
 * Base class for implementing [ApplicationEngine]
 *
 * It creates default engine pipeline, provides [application] property and installs default transformations
 * on respond and receive
 *
 * @param environment instance of [ApplicationEngineEnvironment] for this engine
 * @param pipeline pipeline to use with this engine
 */
public abstract class BaseApplicationEngine(
    public final override val environment: ApplicationEngineEnvironment,
    public val pipeline: EnginePipeline = defaultEnginePipeline(environment)
) : ApplicationEngine {

    /**
     * Configuration for the [BaseApplicationEngine]
     */
    public open class Configuration : ApplicationEngine.Configuration()

    protected val resolvedConnectors: CompletableDeferred<List<EngineConnectorConfig>> = CompletableDeferred()

    private var isFirstLoading by shared(false)
    private var initializedStartAt by shared(currentTimeMillisBridge())

    init {
        BaseApplicationResponse.setupSendPipeline(pipeline.sendPipeline)
        environment.monitor.subscribe(ApplicationStarting) {
            if (!isFirstLoading) {
                initializedStartAt = currentTimeMillisBridge()
            }
            it.receivePipeline.merge(pipeline.receivePipeline)
            it.sendPipeline.merge(pipeline.sendPipeline)
            it.receivePipeline.installDefaultTransformations()
            it.sendPipeline.installDefaultTransformations()
            it.installDefaultInterceptors()
            it.installDefaultTransformationChecker()
        }
        environment.monitor.subscribe(ApplicationStarted) {
            val finishedAt = currentTimeMillisBridge()
            val elapsedTimeInSeconds = (finishedAt - initializedStartAt) / 1_000.0
            if (isFirstLoading) {
                environment.log.info("Application started in $elapsedTimeInSeconds seconds.")
                isFirstLoading = false
            } else {
                environment.log.info("Application auto-reloaded in $elapsedTimeInSeconds seconds.")
            }
        }

        val connectors = resolvedConnectors
        val log = environment.log
        CoroutineScope(environment.application.coroutineContext).launch {
            connectors.await().forEach {
                log.info(
                    "Responding at ${it.type.name.lowercase()}://${it.host}:${it.port}"
                )
            }
        }
    }

    @OptIn(InternalAPI::class)
    private fun Application.installDefaultInterceptors() {
        intercept(ApplicationCallPipeline.Setup) {
            call.response.pipeline.intercept(ApplicationSendPipeline.Before) {
                call.attributes.put(SendPipelineExecutedAttributeKey, Unit)
            }
        }
        intercept(ApplicationCallPipeline.Fallback) {
            val isResponded = call.attributes.getOrNull(SendPipelineExecutedAttributeKey) != null
            if (isResponded) {
                return@intercept
            }
            val status = call.response.status()
                ?: call.attributes.getOrNull(RoutingFailureStatusCode)
                ?: HttpStatusCode.NotFound
            call.respond(status)
        }

        intercept(ApplicationCallPipeline.Call) {
            verifyHostHeader()
        }
    }

    @OptIn(InternalAPI::class)
    private fun Application.installDefaultTransformationChecker() {
        // Respond with "415 Unsupported Media Type" if content cannot be transformed on receive
        intercept(ApplicationCallPipeline.Plugins) {
            try {
                proceed()
            } catch (e: CannotTransformContentToTypeException) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
            }
        }

        sendPipeline.intercept(ApplicationSendPipeline.After) { subject ->
            if (subject !is OutgoingContent) {
                proceedWith(HttpStatusCodeContent(HttpStatusCode.NotAcceptable))
            }
        }
    }

    override suspend fun resolvedConnectors(): List<EngineConnectorConfig> {
        return resolvedConnectors.await()
    }
}

@SharedImmutable
private val SendPipelineExecutedAttributeKey = AttributeKey<Unit>("SendPipelineExecutedAttributeKey")

private suspend fun PipelineContext<Unit, ApplicationCall>.verifyHostHeader() {
    val hostHeaders = call.request.headers.getAll(HttpHeaders.Host) ?: return
    if (hostHeaders.size > 1) {
        call.respond(HttpStatusCode.BadRequest)
        finish()
    }
}
