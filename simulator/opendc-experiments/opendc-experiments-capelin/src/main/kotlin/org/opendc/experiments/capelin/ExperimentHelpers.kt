/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.experiments.capelin

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.MetricProducer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import org.opendc.compute.api.*
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.driver.Host
import org.opendc.compute.service.driver.HostEvent
import org.opendc.compute.service.driver.HostListener
import org.opendc.compute.service.driver.HostState
import org.opendc.compute.service.scheduler.AllocationPolicy
import org.opendc.compute.simulator.SimHost
import org.opendc.experiments.capelin.monitor.ExperimentMonitor
import org.opendc.experiments.capelin.trace.Sc20StreamingParquetTraceReader
import org.opendc.format.environment.EnvironmentReader
import org.opendc.format.trace.TraceReader
import org.opendc.simulator.compute.SimFairShareHypervisorProvider
import org.opendc.simulator.compute.interference.PerformanceInterferenceModel
import org.opendc.simulator.compute.workload.SimWorkload
import org.opendc.simulator.failures.CorrelatedFaultInjector
import org.opendc.simulator.failures.FaultInjector
import org.opendc.telemetry.sdk.metrics.export.CoroutineMetricReader
import java.io.File
import java.time.Clock
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * The logger for this experiment.
 */
private val logger = KotlinLogging.logger {}

/**
 * Construct the failure domain for the experiments.
 */
public fun createFailureDomain(
    coroutineScope: CoroutineScope,
    clock: Clock,
    seed: Int,
    failureInterval: Double,
    service: ComputeService,
    chan: Channel<Unit>
): CoroutineScope {
    val job = coroutineScope.launch {
        chan.receive()
        val random = Random(seed)
        val injectors = mutableMapOf<String, FaultInjector>()
        for (host in service.hosts) {
            val cluster = host.meta["cluster"] as String
            val injector =
                injectors.getOrPut(cluster) {
                    createFaultInjector(
                        this,
                        clock,
                        random,
                        failureInterval
                    )
                }
            injector.enqueue(host as SimHost)
        }
    }
    return CoroutineScope(coroutineScope.coroutineContext + job)
}

/**
 * Obtain the [FaultInjector] to use for the experiments.
 */
public fun createFaultInjector(
    coroutineScope: CoroutineScope,
    clock: Clock,
    random: Random,
    failureInterval: Double
): FaultInjector {
    // Parameters from A. Iosup, A Framework for the Study of Grid Inter-Operation Mechanisms, 2009
    // GRID'5000
    return CorrelatedFaultInjector(
        coroutineScope,
        clock,
        iatScale = ln(failureInterval), iatShape = 1.03, // Hours
        sizeScale = ln(2.0), sizeShape = ln(1.0), // Expect 2 machines, with variation of 1
        dScale = ln(60.0), dShape = ln(60.0 * 8), // Minutes
        random = random
    )
}

/**
 * Create the trace reader from which the VM workloads are read.
 */
public fun createTraceReader(
    path: File,
    performanceInterferenceModel: PerformanceInterferenceModel,
    vms: List<String>,
    seed: Int
): Sc20StreamingParquetTraceReader {
    return Sc20StreamingParquetTraceReader(
        path,
        performanceInterferenceModel,
        vms,
        Random(seed)
    )
}

/**
 * Construct the environment for a simulated compute service..
 */
public suspend fun withComputeService(
    clock: Clock,
    meter: Meter,
    environmentReader: EnvironmentReader,
    allocationPolicy: AllocationPolicy,
    block: suspend CoroutineScope.(ComputeService) -> Unit
): Unit = coroutineScope {
    val hosts = environmentReader
        .use { it.read() }
        .map { def ->
            SimHost(
                def.uid,
                def.name,
                def.model,
                def.meta,
                coroutineContext,
                clock,
                SimFairShareHypervisorProvider(),
                def.powerModel
            )
        }

    val scheduler =
        ComputeService(coroutineContext, clock, meter, allocationPolicy)

    for (host in hosts) {
        scheduler.addHost(host)
    }

    try {
        block(this, scheduler)
    } finally {
        scheduler.close()
        hosts.forEach(SimHost::close)
    }
}

/**
 * Attach the specified monitor to the VM provisioner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun withMonitor(
    monitor: ExperimentMonitor,
    clock: Clock,
    metricProducer: MetricProducer,
    scheduler: ComputeService,
    block: suspend CoroutineScope.() -> Unit
): Unit = coroutineScope {
    val monitorJobs = mutableSetOf<Job>()

    // Monitor host events
    for (host in scheduler.hosts) {
        monitor.reportHostStateChange(clock.millis(), host, HostState.UP)
        host.addListener(object : HostListener {
            override fun onStateChanged(host: Host, newState: HostState) {
                monitor.reportHostStateChange(clock.millis(), host, newState)
            }
        })

        monitorJobs += host.events
            .onEach { event ->
                when (event) {
                    is HostEvent.SliceFinished -> monitor.reportHostSlice(
                        clock.millis(),
                        event.requestedBurst,
                        event.grantedBurst,
                        event.overcommissionedBurst,
                        event.interferedBurst,
                        event.cpuUsage,
                        event.cpuDemand,
                        event.numberOfDeployedImages,
                        event.driver
                    )
                }
            }
            .launchIn(this)

        monitorJobs += (host as SimHost).machine.powerDraw
            .onEach { monitor.reportPowerConsumption(host, it) }
            .launchIn(this)
    }

    val reader = CoroutineMetricReader(
        this, listOf(metricProducer),
        object : MetricExporter {
            override fun export(metrics: Collection<MetricData>): CompletableResultCode {
                val metricsByName = metrics.associateBy { it.name }

                val submittedVms = metricsByName["servers.submitted"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val queuedVms = metricsByName["servers.waiting"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val unscheduledVms = metricsByName["servers.unscheduled"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val runningVms = metricsByName["servers.active"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val finishedVms = metricsByName["servers.finished"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val hosts = metricsByName["hosts.total"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
                val availableHosts = metricsByName["hosts.available"]?.longSumData?.points?.last()?.value?.toInt() ?: 0

                monitor.reportProvisionerMetrics(
                    clock.millis(),
                    hosts,
                    availableHosts,
                    submittedVms,
                    runningVms,
                    finishedVms,
                    queuedVms,
                    unscheduledVms
                )
                return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        },
        exportInterval = 5 * 60 * 1000
    )

    try {
        block(this)
    } finally {
        monitorJobs.forEach(Job::cancel)
        reader.close()
        monitor.close()
    }
}

public class ComputeMetrics {
    public var submittedVms: Int = 0
    public var queuedVms: Int = 0
    public var runningVms: Int = 0
    public var unscheduledVms: Int = 0
    public var finishedVms: Int = 0
}

/**
 * Collect the metrics of the compute service.
 */
public fun collectMetrics(metricProducer: MetricProducer): ComputeMetrics {
    val metrics = metricProducer.collectAllMetrics().associateBy { it.name }
    val res = ComputeMetrics()
    try {
        // Hack to extract metrics from OpenTelemetry SDK
        res.submittedVms = metrics["servers.submitted"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
        res.queuedVms = metrics["servers.waiting"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
        res.unscheduledVms = metrics["servers.unscheduled"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
        res.runningVms = metrics["servers.active"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
        res.finishedVms = metrics["servers.finished"]?.longSumData?.points?.last()?.value?.toInt() ?: 0
    } catch (cause: Throwable) {
        logger.warn(cause) { "Failed to collect metrics" }
    }
    return res
}

/**
 * Process the trace.
 */
public suspend fun processTrace(
    clock: Clock,
    reader: TraceReader<SimWorkload>,
    scheduler: ComputeService,
    chan: Channel<Unit>,
    monitor: ExperimentMonitor
) {
    val client = scheduler.newClient()
    val image = client.newImage("vm-image")
    var offset = Long.MIN_VALUE
    try {
        coroutineScope {
            while (reader.hasNext()) {
                val entry = reader.next()

                if (offset < 0) {
                    offset = entry.start - clock.millis()
                }

                delay(max(0, (entry.start - offset) - clock.millis()))
                launch {
                    chan.send(Unit)
                    val server = client.newServer(
                        entry.name,
                        image,
                        client.newFlavor(
                            entry.name,
                            entry.meta["cores"] as Int,
                            entry.meta["required-memory"] as Long
                        ),
                        meta = entry.meta
                    )

                    suspendCancellableCoroutine { cont ->
                        server.watch(object : ServerWatcher {
                            override fun onStateChanged(server: Server, newState: ServerState) {
                                monitor.reportVmStateChange(clock.millis(), server, newState)

                                if (newState == ServerState.TERMINATED || newState == ServerState.ERROR) {
                                    cont.resume(Unit)
                                }
                            }
                        })
                    }
                }
            }
        }

        yield()
    } finally {
        reader.close()
        client.close()
    }
}
