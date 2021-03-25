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

package org.opendc.compute.service.internal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.opendc.compute.api.*
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.ComputeServiceEvent
import org.opendc.compute.service.driver.Host
import org.opendc.compute.service.driver.HostListener
import org.opendc.compute.service.driver.HostState
import org.opendc.compute.service.scheduler.AllocationPolicy
import org.opendc.utils.TimerScheduler
import org.opendc.utils.flow.EventFlow
import java.time.Clock
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

/**
 * Internal implementation of the OpenDC Compute service.
 *
 * @param context The [CoroutineContext] to use.
 * @param clock The clock instance to keep track of time.
 */
internal class ComputeServiceImpl(
    private val context: CoroutineContext,
    private val clock: Clock,
    private val allocationPolicy: AllocationPolicy,
    private val schedulingQuantum: Long
) : ComputeService, HostListener {
    /**
     * The [CoroutineScope] of the service bounded by the lifecycle of the service.
     */
    private val scope = CoroutineScope(context + Job())

    /**
     * The logger instance of this server.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * The [Random] instance used to generate unique identifiers for the objects.
     */
    private val random = Random(0)

    /**
     * A mapping from host to host view.
     */
    private val hostToView = mutableMapOf<Host, HostView>()

    /**
     * The available hypervisors.
     */
    private val availableHosts: MutableSet<HostView> = mutableSetOf()

    /**
     * The servers that should be launched by the service.
     */
    private val queue: Deque<SchedulingRequest> = ArrayDeque()

    /**
     * The active servers in the system.
     */
    private val activeServers: MutableMap<Server, Host> = mutableMapOf()

    /**
     * The registered flavors for this compute service.
     */
    internal val flavors = mutableMapOf<UUID, InternalFlavor>()

    /**
     * The registered images for this compute service.
     */
    internal val images = mutableMapOf<UUID, InternalImage>()

    /**
     * The registered servers for this compute service.
     */
    private val servers = mutableMapOf<UUID, InternalServer>()

    private var submittedVms: Int = 0
    private var queuedVms: Int = 0
    private var runningVms: Int = 0
    private var finishedVms: Int = 0
    private var unscheduledVms: Int = 0

    private var maxCores = 0
    private var maxMemory = 0L

    /**
     * The allocation logic to use.
     */
    private val allocationLogic = allocationPolicy()

    override val events: Flow<ComputeServiceEvent>
        get() = _events
    private val _events = EventFlow<ComputeServiceEvent>()

    /**
     * The [TimerScheduler] to use for scheduling the scheduler cycles.
     */
    private var scheduler: TimerScheduler<Unit> = TimerScheduler(scope.coroutineContext, clock)

    override val hosts: Set<Host>
        get() = hostToView.keys

    override val hostCount: Int
        get() = hostToView.size

    override fun newClient(): ComputeClient {
        check(scope.isActive) { "Service is already closed" }
        return object : ComputeClient {
            private var isClosed: Boolean = false

            override suspend fun queryFlavors(): List<Flavor> {
                check(!isClosed) { "Client is already closed" }

                return flavors.values.map { ClientFlavor(it) }
            }

            override suspend fun findFlavor(id: UUID): Flavor? {
                check(!isClosed) { "Client is already closed" }

                return flavors[id]?.let { ClientFlavor(it) }
            }

            override suspend fun newFlavor(
                name: String,
                cpuCount: Int,
                memorySize: Long,
                labels: Map<String, String>,
                meta: Map<String, Any>
            ): Flavor {
                check(!isClosed) { "Client is already closed" }

                val uid = UUID(clock.millis(), random.nextLong())
                val flavor = InternalFlavor(
                    this@ComputeServiceImpl,
                    uid,
                    name,
                    cpuCount,
                    memorySize,
                    labels,
                    meta
                )

                flavors[uid] = flavor

                return ClientFlavor(flavor)
            }

            override suspend fun queryImages(): List<Image> {
                check(!isClosed) { "Client is already closed" }

                return images.values.map { ClientImage(it) }
            }

            override suspend fun findImage(id: UUID): Image? {
                check(!isClosed) { "Client is already closed" }

                return images[id]?.let { ClientImage(it) }
            }

            override suspend fun newImage(name: String, labels: Map<String, String>, meta: Map<String, Any>): Image {
                check(!isClosed) { "Client is already closed" }

                val uid = UUID(clock.millis(), random.nextLong())
                val image = InternalImage(this@ComputeServiceImpl, uid, name, labels, meta)

                images[uid] = image

                return ClientImage(image)
            }

            override suspend fun newServer(
                name: String,
                image: Image,
                flavor: Flavor,
                labels: Map<String, String>,
                meta: Map<String, Any>,
                start: Boolean
            ): Server {
                check(!isClosed) { "Client is closed" }
                _events.emit(
                    ComputeServiceEvent.MetricsAvailable(
                        this@ComputeServiceImpl,
                        hostCount,
                        availableHosts.size,
                        ++submittedVms,
                        runningVms,
                        finishedVms,
                        ++queuedVms,
                        unscheduledVms
                    )
                )

                val uid = UUID(clock.millis(), random.nextLong())
                val server = InternalServer(
                    this@ComputeServiceImpl,
                    uid,
                    name,
                    requireNotNull(flavors[flavor.uid]) { "Unknown flavor" },
                    requireNotNull(images[image.uid]) { "Unknown image" },
                    labels.toMutableMap(),
                    meta.toMutableMap()
                )

                servers[uid] = server

                if (start) {
                    server.start()
                }

                return ClientServer(server)
            }

            override suspend fun findServer(id: UUID): Server? {
                check(!isClosed) { "Client is already closed" }

                return servers[id]?.let { ClientServer(it) }
            }

            override suspend fun queryServers(): List<Server> {
                check(!isClosed) { "Client is already closed" }

                return servers.values.map { ClientServer(it) }
            }

            override fun close() {
                isClosed = true
            }

            override fun toString(): String = "ComputeClient"
        }
    }

    override fun addHost(host: Host) {
        // Check if host is already known
        if (host in hostToView) {
            return
        }

        val hv = HostView(host)
        maxCores = max(maxCores, host.model.cpuCount)
        maxMemory = max(maxMemory, host.model.memorySize)
        hostToView[host] = hv

        if (host.state == HostState.UP) {
            availableHosts += hv
        }

        host.addListener(this)
    }

    override fun removeHost(host: Host) {
        host.removeListener(this)
    }

    override fun close() {
        scope.cancel()
    }

    internal fun schedule(server: InternalServer): SchedulingRequest {
        logger.debug { "Enqueueing server ${server.uid} to be assigned to host." }

        val request = SchedulingRequest(server)
        queue.add(request)
        requestSchedulingCycle()
        return request
    }

    internal fun delete(flavor: InternalFlavor) {
        flavors.remove(flavor.uid)
    }

    internal fun delete(image: InternalImage) {
        images.remove(image.uid)
    }

    internal fun delete(server: InternalServer) {
        servers.remove(server.uid)
    }

    /**
     * Indicate that a new scheduling cycle is needed due to a change to the service's state.
     */
    private fun requestSchedulingCycle() {
        // Bail out in case we have already requested a new cycle or the queue is empty.
        if (scheduler.isTimerActive(Unit) || queue.isEmpty()) {
            return
        }

        // We assume that the provisioner runs at a fixed slot every time quantum (e.g t=0, t=60, t=120).
        // This is important because the slices of the VMs need to be aligned.
        // We calculate here the delay until the next scheduling slot.
        val delay = schedulingQuantum - (clock.millis() % schedulingQuantum)

        scheduler.startSingleTimer(Unit, delay) {
            doSchedule()
        }
    }

    /**
     * Run a single scheduling iteration.
     */
    private fun doSchedule() {
        while (queue.isNotEmpty()) {
            val request = queue.peek()

            if (request.isCancelled) {
                queue.poll()
                continue
            }

            val server = request.server
            val hv = allocationLogic.select(availableHosts, request.server)
            if (hv == null || !hv.host.canFit(server)) {
                logger.trace { "Server $server selected for scheduling but no capacity available for it at the moment" }

                if (server.flavor.memorySize > maxMemory || server.flavor.cpuCount > maxCores) {
                    _events.emit(
                        ComputeServiceEvent.MetricsAvailable(
                            this@ComputeServiceImpl,
                            hostCount,
                            availableHosts.size,
                            submittedVms,
                            runningVms,
                            finishedVms,
                            --queuedVms,
                            ++unscheduledVms
                        )
                    )

                    // Remove the incoming image
                    queue.poll()

                    logger.warn("Failed to spawn $server: does not fit [${clock.millis()}]")

                    server.state = ServerState.ERROR
                    continue
                } else {
                    break
                }
            }

            val host = hv.host

            // Remove request from queue
            queue.poll()

            logger.info { "Assigned server $server to host $host." }

            // Speculatively update the hypervisor view information to prevent other images in the queue from
            // deciding on stale values.
            hv.numberOfActiveServers++
            hv.provisionedCores += server.flavor.cpuCount
            hv.availableMemory -= server.flavor.memorySize // XXX Temporary hack

            scope.launch {
                try {
                    server.host = host
                    host.spawn(server)
                    activeServers[server] = host

                    _events.emit(
                        ComputeServiceEvent.MetricsAvailable(
                            this@ComputeServiceImpl,
                            hostCount,
                            availableHosts.size,
                            submittedVms,
                            ++runningVms,
                            finishedVms,
                            --queuedVms,
                            unscheduledVms
                        )
                    )
                } catch (e: Throwable) {
                    logger.error("Failed to deploy VM", e)

                    hv.numberOfActiveServers--
                    hv.provisionedCores -= server.flavor.cpuCount
                    hv.availableMemory += server.flavor.memorySize
                }
            }
        }
    }

    /**
     * A request to schedule an [InternalServer] onto one of the [Host]s.
     */
    internal data class SchedulingRequest(val server: InternalServer) {
        /**
         * A flag to indicate that the request is cancelled.
         */
        var isCancelled: Boolean = false
    }

    override fun onStateChanged(host: Host, newState: HostState) {
        when (newState) {
            HostState.UP -> {
                logger.debug { "[${clock.millis()}] Host ${host.uid} state changed: $newState" }

                val hv = hostToView[host]
                if (hv != null) {
                    // Corner case for when the hypervisor already exists
                    availableHosts += hv
                }

                _events.emit(
                    ComputeServiceEvent.MetricsAvailable(
                        this@ComputeServiceImpl,
                        hostCount,
                        availableHosts.size,
                        submittedVms,
                        runningVms,
                        finishedVms,
                        queuedVms,
                        unscheduledVms
                    )
                )

                // Re-schedule on the new machine
                requestSchedulingCycle()
            }
            HostState.DOWN -> {
                logger.debug { "[${clock.millis()}] Host ${host.uid} state changed: $newState" }

                val hv = hostToView[host] ?: return
                availableHosts -= hv

                _events.emit(
                    ComputeServiceEvent.MetricsAvailable(
                        this@ComputeServiceImpl,
                        hostCount,
                        availableHosts.size,
                        submittedVms,
                        runningVms,
                        finishedVms,
                        queuedVms,
                        unscheduledVms
                    )
                )

                requestSchedulingCycle()
            }
        }
    }

    override fun onStateChanged(host: Host, server: Server, newState: ServerState) {
        require(server is InternalServer) { "Invalid server type passed to service" }

        if (server.host != host) {
            // This can happen when a server is rescheduled and started on another machine, while being deleted from
            // the old machine.
            return
        }

        server.state = newState

        if (newState == ServerState.TERMINATED || newState == ServerState.DELETED) {
            logger.info { "[${clock.millis()}] Server ${server.uid} ${server.name} ${server.flavor} finished." }

            _events.emit(
                ComputeServiceEvent.MetricsAvailable(
                    this@ComputeServiceImpl,
                    hostCount,
                    availableHosts.size,
                    submittedVms,
                    --runningVms,
                    ++finishedVms,
                    queuedVms,
                    unscheduledVms
                )
            )

            activeServers -= server
            val hv = hostToView[host]
            if (hv != null) {
                hv.provisionedCores -= server.flavor.cpuCount
                hv.numberOfActiveServers--
                hv.availableMemory += server.flavor.memorySize
            } else {
                logger.error { "Unknown host $host" }
            }

            // Try to reschedule if needed
            requestSchedulingCycle()
        }
    }
}
