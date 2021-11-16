/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.application.plugins.api

import io.ktor.server.application.*
import io.ktor.util.pipeline.*

/**
 * A [PluginBuilderBase] that allows you to insert the [currentPlugin] actions before/after [otherPlugins].
 **/
public abstract class RelativePluginBuilder(
    private val currentPlugin: PluginBuilder<*>,
    private val otherPlugins: List<PluginBuilder<*>>
) : PluginBuilderBase {
    private fun <T : Any> sortedPhases(
        interceptions: List<Interception<T>>,
        pipeline: Pipeline<*, ApplicationCall>,
        otherPlugin: PluginBuilder<*>
    ): List<PipelinePhase> =
        interceptions
            .map { it.phase }
            .sortedBy {
                if (!pipeline.items.contains(it)) {
                    throw MissingApplicationPluginException(otherPlugin.key)
                }

                pipeline.items.indexOf(it)
            }

    /**
     * Specifies how to select a phase from a sorted list of pipeline phases of another plugin.
     * After a phase is selected, it is passed to the [insertPhase] method as [relativePhase].
     **/
    protected abstract fun selectPhase(phases: List<PipelinePhase>): PipelinePhase?

    /**
     * Specifies how to insert a [newPhase] relatively to a [relativePhase] of another plugin.
     **/
    protected abstract fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    )

    private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelativelyWithMessage(
        currentInterceptions: MutableList<Interception<T>>,
        otherInterceptionsList: List<MutableList<Interception<T>>>,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall, Any) -> Unit
    ) {
        val currentPhase = currentPlugin.newPhase()

        currentInterceptions.add(
            Interception(
                currentPhase,
                action = { pipeline ->
                    for (i in otherPlugins.indices) {
                        val otherPlugin = otherPlugins[i]
                        val otherInterceptions = otherInterceptionsList[i]

                        val otherPhases = sortedPhases(otherInterceptions, pipeline, otherPlugin)
                        selectPhase(otherPhases)?.let { lastDependentPhase ->
                            insertPhase(pipeline, lastDependentPhase, currentPhase)
                        }
                    }

                    pipeline.intercept(currentPhase) {
                        contextInit(this).block(call, subject)
                    }
                }
            )
        )
    }

    private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelatively(
        currentInterceptions: MutableList<Interception<T>>,
        otherInterceptions: List<MutableList<Interception<T>>>,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall) -> Unit
    ) = insertToPhaseRelativelyWithMessage(currentInterceptions, otherInterceptions, contextInit) { call, _ ->
        block(call)
    }

    override val onCall: OnCall = object : OnCall {
        override operator fun invoke(block: suspend CallContext.(ApplicationCall) -> Unit) {
            this@RelativePluginBuilder.insertToPhaseRelatively(
                this@RelativePluginBuilder.currentPlugin.callInterceptions,
                this@RelativePluginBuilder.otherPlugins.map { it.callInterceptions },
                ::CallContext
            ) { call -> block(call) }
        }
    }

    override val onCallReceive: OnCallReceive = object : OnCallReceive {
        override operator fun invoke(block: suspend CallReceiveContext.(ApplicationCall) -> Unit) {
            this@RelativePluginBuilder.insertToPhaseRelatively(
                this@RelativePluginBuilder.currentPlugin.onReceiveInterceptions,
                this@RelativePluginBuilder.otherPlugins.map { it.onReceiveInterceptions },
                ::CallReceiveContext,
                block
            )
        }
    }

    override val onCallRespond: OnCallRespond = object : OnCallRespond {
        override operator fun invoke(block: suspend CallRespondContext.(ApplicationCall) -> Unit) {
            this@RelativePluginBuilder.insertToPhaseRelatively(
                this@RelativePluginBuilder.currentPlugin.onResponseInterceptions,
                this@RelativePluginBuilder.otherPlugins.map { it.onResponseInterceptions },
                ::CallRespondContext,
                block
            )
        }

        override fun afterTransform(
            block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit
        ) {
            this@RelativePluginBuilder.insertToPhaseRelativelyWithMessage(
                this@RelativePluginBuilder.currentPlugin.afterResponseInterceptions,
                this@RelativePluginBuilder.otherPlugins.map { it.afterResponseInterceptions },
                ::CallRespondAfterTransformContext,
                block
            )
        }
    }

    @Deprecated(
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("this@createPlugin.applicationShutdownHook"),
        message = "Please note that applicationShutdownHook is not guaranteed to be executed before " +
            "or after another plugin"
    )
    public override fun applicationShutdownHook(hook: (Application) -> Unit) {
        currentPlugin.environment?.monitor?.subscribe(ApplicationStopped) { app ->
            hook(app)
        }
    }
}

/**
 * Contains handlers executed after the same handler is finished for all [otherPlugins].
 **/
public class AfterPluginsBuilder(
    currentPlugin: PluginBuilder<*>,
    otherPlugins: List<PluginBuilder<*>>
) : RelativePluginBuilder(currentPlugin, otherPlugins) {
    override fun selectPhase(phases: List<PipelinePhase>): PipelinePhase? = phases.lastOrNull()

    override fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    ) {
        pipeline.insertPhaseAfter(relativePhase, newPhase)
    }
}

/**
 * Contains handlers executed before the same handler is finished for all [otherPlugins].
 **/
@PluginsDslMarker
public class BeforePluginsBuilder(
    currentPlugin: PluginBuilder<*>,
    otherPlugins: List<PluginBuilder<*>>
) : RelativePluginBuilder(currentPlugin, otherPlugins) {
    override fun selectPhase(phases: List<PipelinePhase>): PipelinePhase? = phases.firstOrNull()

    override fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    ) {
        pipeline.insertPhaseBefore(relativePhase, newPhase)
    }
}
