/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.cluster.ClusterState;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.Node;
import com.hazelcast.internal.cluster.ClusterService;
import com.hazelcast.internal.partition.impl.InternalPartitionServiceImpl;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.JobAlreadyExistsException;
import com.hazelcast.jet.Util;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.JobNotFoundException;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.TopologyChangedException;
import com.hazelcast.jet.impl.exception.EnteringPassiveClusterStateException;
import com.hazelcast.jet.impl.operation.GetClusterMetadataOperation;
import com.hazelcast.jet.impl.operation.NotifyMemberShutdownOperation;
import com.hazelcast.jet.impl.util.LoggingUtil;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.executionservice.InternalExecutionService;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.util.Clock;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.cluster.ClusterState.IN_TRANSITION;
import static com.hazelcast.cluster.ClusterState.PASSIVE;
import static com.hazelcast.cluster.memberselector.MemberSelectors.DATA_MEMBER_SELECTOR;
import static com.hazelcast.jet.Util.idToString;
import static com.hazelcast.jet.core.JobStatus.COMPLETING;
import static com.hazelcast.jet.core.JobStatus.NOT_RUNNING;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static com.hazelcast.jet.core.JobStatus.SUSPENDED;
import static com.hazelcast.jet.impl.TerminationMode.CANCEL_FORCEFUL;
import static com.hazelcast.jet.impl.execution.init.CustomClassLoadedObject.deserializeWithCustomClassLoader;
import static com.hazelcast.jet.impl.util.ExceptionUtil.sneakyThrow;
import static com.hazelcast.jet.impl.util.ExceptionUtil.withTryCatch;
import static com.hazelcast.jet.impl.util.JetProperties.JOB_SCAN_PERIOD;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFine;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFinest;
import static com.hazelcast.jet.impl.util.Util.getJetInstance;
import static com.hazelcast.util.executor.ExecutorType.CACHED;
import static java.util.Comparator.comparing;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * A service that handles MasterContexts on the coordinator member.
 * Job-control operations from client are handled here.
 */
public class JobCoordinationService {

    private static final String COORDINATOR_EXECUTOR_NAME = "jet:coordinator";

    /**
     * The delay before retrying to start/scale up a job.
     */
    private static final long RETRY_DELAY_IN_MILLIS = SECONDS.toMillis(2);
    private static final ThreadLocal<Boolean> IS_JOB_COORDINATOR_THREAD = ThreadLocal.withInitial(() -> false);

    private final NodeEngineImpl nodeEngine;
    private final JetService jetService;
    private final JetConfig config;
    private final ILogger logger;
    private final JobRepository jobRepository;
    private final ConcurrentMap<Long, MasterContext> masterContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> membersShuttingDown = new ConcurrentHashMap<>();
    /**
     * Map of {memberUuid; removeTime}.
     *
     * A collection of UUIDs of members which left the cluster and for which we
     * didn't receive {@link NotifyMemberShutdownOperation}.
     */
    private final Map<String, Long> removedMembers = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile boolean isClusterEnteringPassiveState;
    private volatile boolean jobsScanned;

    private final AtomicInteger scaleUpScheduledCount = new AtomicInteger();

    JobCoordinationService(NodeEngineImpl nodeEngine, JetService jetService, JetConfig config,
                           JobRepository jobRepository) {
        this.nodeEngine = nodeEngine;
        this.jetService = jetService;
        this.config = config;
        this.logger = nodeEngine.getLogger(getClass());
        this.jobRepository = jobRepository;
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    void startScanningForJobs() {
        InternalExecutionService executionService = nodeEngine.getExecutionService();
        HazelcastProperties properties = new HazelcastProperties(config.getProperties());
        long jobScanPeriodInMillis = properties.getMillis(JOB_SCAN_PERIOD);
        executionService.register(COORDINATOR_EXECUTOR_NAME, 2, Integer.MAX_VALUE, CACHED);
        executionService.scheduleWithRepetition(COORDINATOR_EXECUTOR_NAME, this::scanJobs,
                0, jobScanPeriodInMillis, MILLISECONDS);
    }

    public CompletableFuture<Void> submitJob(long jobId, Data dag, Data serializedConfig) {
        return submitToCoordinatorThread(() -> {
            JobConfig config = nodeEngine.getSerializationService().toObject(serializedConfig);
            assertIsMaster("Cannot submit job " + idToString(jobId) + " to non-master node");
            checkOperationalState();

            // the order of operations is important.

            // first, check if the job is already completed
            JobResult jobResult = jobRepository.getJobResult(jobId);
            if (jobResult != null) {
                logger.fine("Not starting job " + idToString(jobId) + " since already completed with result: "
                        + jobResult);
                return;
            }

            int quorumSize = config.isSplitBrainProtectionEnabled() ? getQuorumSize() : 0;
            String dagJson = dagToJson(jobId, config, dag);
            JobRecord jobRecord = new JobRecord(jobId, Clock.currentTimeMillis(), dag, dagJson, config);
            JobExecutionRecord jobExecutionRecord = new JobExecutionRecord(jobId, quorumSize, false);
            MasterContext masterContext = createMasterContext(jobRecord, jobExecutionRecord);

            boolean hasDuplicateJobName;
            synchronized (lock) {
                assertIsMaster("Cannot submit job " + idToString(jobId) + " to non-master node");
                checkOperationalState();
                hasDuplicateJobName = config.getName() != null && hasActiveJobWithName(config.getName());
                if (!hasDuplicateJobName) {
                    // just try to initiate the coordination
                    MasterContext prev = masterContexts.putIfAbsent(jobId, masterContext);
                    if (prev != null) {
                        logger.fine("Joining to already existing masterContext " + prev.jobIdString());
                        return;
                    }
                }
            }

            if (hasDuplicateJobName) {
                jobRepository.deleteJob(jobId);
                throw new JobAlreadyExistsException("Another active job with equal name (" + config.getName()
                        + ") exists: " + idToString(jobId));
            }

            // If job is not currently running, it might be that it is just completed
            if (completeMasterContextIfJobAlreadyCompleted(masterContext)) {
                return;
            }

            // If there is no master context and job result at the same time, it means this is the first submission
            jobRepository.putNewJobRecord(jobRecord);

            logger.info("Starting job " + idToString(masterContext.jobId()) + " based on submit request");
            nodeEngine.getExecutionService().execute(COORDINATOR_EXECUTOR_NAME, () -> tryStartJob(masterContext));
        });
    }

    @SuppressWarnings("WeakerAccess") // used by jet-enterprise
    MasterContext createMasterContext(JobRecord jobRecord, JobExecutionRecord jobExecutionRecord) {
        return new MasterContext(nodeEngine, this, jobRecord, jobExecutionRecord);
    }

    private boolean hasActiveJobWithName(@Nonnull String jobName) {
        // if scanJob() has not run yet, master context objects may not be initialized.
        // in this case, we cannot check if the new job submission has a duplicate job name.
        // therefore, we will retry until scanJob() task runs at least once.
        if (!jobsScanned) {
            throw new RetryableHazelcastException("Cannot submit job with name '" + jobName
                    + "' before the master node initializes job coordination service state");
        }

        return masterContexts.values()
                             .stream()
                             .anyMatch(ctx -> jobName.equals(ctx.jobConfig().getName()));
    }

    public CompletableFuture<Void> prepareForPassiveClusterState() {
        assertIsMaster("Cannot prepare for passive cluster state on a non-master node");
        synchronized (lock) {
            isClusterEnteringPassiveState = true;
        }
        CompletableFuture[] futures = masterContexts
                .values().stream()
                .map(mc -> mc.jobContext().gracefullyTerminate())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    void clusterChangeDone() {
        synchronized (lock) {
            isClusterEnteringPassiveState = false;
        }
    }

    public void reset() {
        assert !isMaster() : "this member is a master";
        List<MasterContext> contexts;
        synchronized (lock) {
            contexts = new ArrayList<>(masterContexts.values());
            masterContexts.clear();
            jobsScanned = false;
        }

        contexts.forEach(ctx -> ctx.jobContext().setFinalResult(new CancellationException()));
    }

    public CompletableFuture<Void> joinSubmittedJob(long jobId) {
        assertIsMaster("Cannot join job " + idToString(jobId) + " on non-master node");
        checkOperationalState();

        return submitToCoordinatorThread(() -> {
            JobRecord jobRecord = jobRepository.getJobRecord(jobId);
            if (jobRecord != null) {
                JobExecutionRecord jobExecutionRecord = ensureExecutionRecord(jobId,
                        jobRepository.getJobExecutionRecord(jobId));
                return startJobIfNotStartedOrCompleted(jobRecord, jobExecutionRecord, "join request from client");
            }

            JobResult jobResult = jobRepository.getJobResult(jobId);
            if (jobResult != null) {
                return jobResult.asCompletableFuture();
            }

            throw new JobNotFoundException(jobId);
        }).thenCompose(identity()); // unwrap the inner future
    }

    public CompletableFuture<Void> terminateJob(long jobId, TerminationMode terminationMode) {
        assertIsMaster("Cannot " + terminationMode + " job " + idToString(jobId) + " on non-master node");

        return submitToCoordinatorThread(() -> {
            JobResult jobResult = jobRepository.getJobResult(jobId);
            if (jobResult != null) {
                if (terminationMode == CANCEL_FORCEFUL) {
                    logger.fine("Ignoring cancellation of a completed job " + idToString(jobId));
                    return;
                }
                throw new IllegalStateException("Cannot " + terminationMode + " job " + idToString(jobId)
                        + " because it already has a result: " + jobResult);
            }

            MasterContext masterContext = masterContexts.get(jobId);
            if (masterContext == null) {
                JobRecord jobRecord = jobRepository.getJobRecord(jobId);
                String message = "No MasterContext found for job " + idToString(jobId) + " for " + terminationMode;
                if (jobRecord != null) {
                    // we'll eventually learn of the job through scanning of records or from a join operation
                    throw new RetryableHazelcastException(message);
                }
                throw new JobNotFoundException(jobId);
            }

            // User can cancel in any state, other terminations are allowed only when running.
            // This is not technically required (we can request termination in any state),
            // but this method is only called from client. It would be weird for the client to
            // request a restart if the job didn't start yet etc.
            // Also, it would be weird to restart the job during STARTING: as soon as it will start,
            // it will restart.
            // In any case, it doesn't make sense to restart a suspended job.
            JobStatus jobStatus = masterContext.jobStatus();
            if (jobStatus != RUNNING && terminationMode != CANCEL_FORCEFUL) {
                throw new IllegalStateException("Cannot " + terminationMode + ", job status is " + jobStatus
                        + ", should be " + RUNNING);
            }

            String terminationResult = masterContext.jobContext().requestTermination(terminationMode, false).f1();
            if (terminationResult != null) {
                throw new IllegalStateException("Cannot " + terminationMode + ": " + terminationResult);
            }
        });
    }

    public CompletableFuture<List<Long>> getAllJobIds() {
        assertIsMaster("Cannot query list of job ids on non-master node");

        return submitToCoordinatorThread(() -> {
            Set<Long> jobIds = new HashSet<>(jobRepository.getAllJobIds());
            jobIds.addAll(masterContexts.keySet());
            return new ArrayList<>(jobIds);
        });
    }

    /**
     * Return the job IDs of jobs with given name, sorted by {active/completed, creation time}, active & newest first.
     */
    public CompletableFuture<List<Long>> getJobIds(@Nonnull String name) {
        assertIsMaster("Cannot query list of job ids on non-master node");

        return submitToCoordinatorThread(() -> {
            Map<Long, Long> jobs = jobRepository.getJobResults(name).stream()
                    .collect(toMap(JobResult::getJobId, JobResult::getCreationTime));

            for (MasterContext ctx : masterContexts.values()) {
                if (name.equals(ctx.jobConfig().getName())) {
                    jobs.putIfAbsent(ctx.jobId(), Long.MAX_VALUE);
                }
            }

            return jobs.entrySet().stream()
                       .sorted(comparing(Entry<Long, Long>::getValue).reversed())
                       .map(Entry::getKey)
                       .collect(toList());
        });
    }

    /**
     * Returns the job status or fails with {@link JobNotFoundException}
     * if the requested job is not found.
     */
    public CompletableFuture<JobStatus> getJobStatus(long jobId) {
        assertIsMaster("Cannot query status of job " + idToString(jobId) + " on non-master node");

        return submitToCoordinatorThread(() -> {
            // first check if there is a job result present.
            // this map is updated first during completion.
            JobResult jobResult = jobRepository.getJobResult(jobId);
            if (jobResult != null) {
                return jobResult.getJobStatus();
            }

            // check if there a master context for running job
            MasterContext currentMasterContext = masterContexts.get(jobId);
            if (currentMasterContext != null) {
                JobStatus jobStatus = currentMasterContext.jobStatus();
                if (jobStatus == RUNNING && currentMasterContext.jobContext().requestedTerminationMode() != null) {
                    return COMPLETING;
                }
                return jobStatus;
            }

            // no master context found, job might be just submitted
            JobExecutionRecord jobExecutionRecord = jobRepository.getJobExecutionRecord(jobId);
            if (jobExecutionRecord != null) {
                return jobExecutionRecord.isSuspended() ? SUSPENDED : NOT_RUNNING;
            } else {
                // no job record found, but check job results again
                // since job might have been completed meanwhile.
                jobResult = jobRepository.getJobResult(jobId);
                if (jobResult != null) {
                    return jobResult.getJobStatus();
                }
                throw new JobNotFoundException(jobId);
            }
        });
    }

    /**
     * Returns the job submission time or fails with {@link JobNotFoundException}
     * if the requested job is not found.
     */
    public CompletableFuture<Long> getJobSubmissionTime(long jobId) {
        assertIsMaster("Cannot query submission time of job " + idToString(jobId) + " on non-master node");

        return submitToCoordinatorThread(() -> {
            JobRecord jobRecord = jobRepository.getJobRecord(jobId);
            if (jobRecord != null) {
                return jobRecord.getCreationTime();
            }

            JobResult jobResult = jobRepository.getJobResult(jobId);
            if (jobResult != null) {
                return jobResult.getCreationTime();
            }

            throw new JobNotFoundException(jobId);
        });
    }

    public void resumeJob(long jobId) {
        assertIsMaster("Cannot resume job " + idToString(jobId) + " on non-master node");

        MasterContext masterContext = masterContexts.get(jobId);
        if (masterContext == null) {
            throw new JobNotFoundException("MasterContext not found to resume job " + idToString(jobId));
        }
        masterContext.jobContext().resumeJob(jobRepository::newExecutionId);
    }

    /**
     * Return a summary of all jobs
     */
    public CompletableFuture<List<JobSummary>> getJobSummaryList() {
        return submitToCoordinatorThread(() -> {
            Map<Long, JobSummary> jobs = new HashMap<>();

            // running jobs
            jobRepository.getJobRecords().stream().map(this::getJobSummary).forEach(s -> jobs.put(s.getJobId(), s));

            // completed jobs
            jobRepository.getJobResults().stream()
                         .map(r -> new JobSummary(
                                 r.getJobId(), r.getJobNameOrId(), r.getJobStatus(), r.getCreationTime(),
                                 r.getCompletionTime(), r.getFailureText())
                         ).forEach(s -> jobs.put(s.getJobId(), s));

            return jobs.values().stream().sorted(comparing(JobSummary::getSubmissionTime).reversed()).collect(toList());
        });
    }

    /**
     * Add the given member to shutting down members. This will prevent
     * submission of more executions until the member actually leaves the
     * cluster. The returned future will complete when all executions of which
     * the member is a participant terminate.
     * <p>
     * The method is idempotent, the {@link NotifyMemberShutdownOperation}
     * which calls it can be retried.
     */
    @Nonnull
    public CompletableFuture<Void> addShuttingDownMember(String uuid) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> oldFuture = membersShuttingDown.putIfAbsent(uuid, future);
        if (oldFuture != null) {
            return oldFuture;
        }
        if (removedMembers.containsKey(uuid)) {
            logFine(logger, "NotifyMemberShutdownOperation received for a member that was already " +
                    "removed from the cluster: %s", uuid);
            return CompletableFuture.completedFuture(null);
        }
        logFine(logger, "Added a shutting-down member: %s", uuid);
        CompletableFuture[] futures = masterContexts.values().stream()
                                                    .map(mc -> mc.jobContext().onParticipantGracefulShutdown(uuid))
                                                    .toArray(CompletableFuture[]::new);
        // Need to do this even if futures.length == 0, we need to perform the action in whenComplete
        CompletableFuture.allOf(futures)
                         .whenComplete(withTryCatch(logger, (r, e) -> future.complete(null)));
        return future;
    }

    // only for testing
    public Map<Long, MasterContext> getMasterContexts() {
        return new HashMap<>(masterContexts);
    }

    // only for testing
    public MasterContext getMasterContext(long jobId) {
        return masterContexts.get(jobId);
    }

    JetService getJetService() {
        return jetService;
    }

    boolean shouldStartJobs() {
        ClusterState clusterState = nodeEngine.getClusterService().getClusterState();
        if (!isMaster() || !nodeEngine.isRunning() || isClusterEnteringPassiveState
                || clusterState == PASSIVE || clusterState == IN_TRANSITION) {
            return false;
        }
        if (!allMembersHaveSameState(clusterState)) {
            LoggingUtil.logFine(logger, "Not starting jobs because not all members have the same state: %s",
                    clusterState);
            return false;
        }
        // if there are any members in a shutdown process, don't start jobs
        if (!membersShuttingDown.isEmpty()) {
            LoggingUtil.logFine(logger, "Not starting jobs because members are shutting down: %s",
                    membersShuttingDown.keySet());
            return false;
        }
        InternalPartitionServiceImpl partitionService = getInternalPartitionService();
        return partitionService.getPartitionStateManager().isInitialized()
                && partitionService.areMigrationTasksAllowed()
                && !partitionService.hasOnGoingMigrationLocal();
    }

    /**
     * Returns {@code true} if all members except for the local member have
     * state equal to the given {@code clusterState}. Ignores members that just
     * left the cluster. Any failure when querying the state on any member
     * causes the method to return {@code false}.
     */
    private boolean allMembersHaveSameState(ClusterState clusterState) {
        // TODO remove once the issue is fixed on the imdg side
        try {
            Set<Member> members = nodeEngine.getClusterService().getMembers();
            List<Future<ClusterMetadata>> futures =
                    members.stream()
                           .filter(member -> !member.localMember())
                           .map(this::clusterMetadataAsync)
                           .collect(toList());
            return futures.stream()
                          .map(future -> {
                              try {
                                  return future.get();
                              } catch (ExecutionException e) {
                                  if (e.getCause() instanceof MemberLeftException
                                          || e.getCause() instanceof TargetNotMemberException) {
                                      // ignore these exceptions
                                      return null;
                                  }
                                  throw sneakyThrow(e);
                              } catch (Exception e) {
                                  throw sneakyThrow(e);
                              }
                          })
                          .filter(Objects::nonNull)
                          .allMatch(metaData -> metaData.getState() == clusterState);
        } catch (Exception e) {
            logger.warning("Exception during member state check", e);
            return false;
        }
    }

    private Future<ClusterMetadata> clusterMetadataAsync(Member member) {
        return nodeEngine.getOperationService().invokeOnTarget(JetService.SERVICE_NAME,
                new GetClusterMetadataOperation(), member.getAddress());
    }

    void onMemberAdded(MemberImpl addedMember) {
        // the member can re-join with the same UUID in certain scenarios
        removedMembers.remove(addedMember.getUuid());
        if (addedMember.isLiteMember()) {
            return;
        }

        updateQuorumValues();
        scheduleScaleUp(config.getInstanceConfig().getScaleUpDelayMillis());
    }

    void onMemberRemoved(String uuid) {
        if (membersShuttingDown.remove(uuid) != null) {
            logFine(logger, "Removed a shutting-down member: %s, now shuttingDownMembers=%s",
                    uuid, membersShuttingDown.keySet());
        } else {
            removedMembers.put(uuid, System.nanoTime());
        }

        // clean up old entries from removedMembers (the value is time when the member was removed)
        long removeThreshold = System.nanoTime() - HOURS.toNanos(1);
        removedMembers.entrySet().removeIf(en -> en.getValue() < removeThreshold);
    }

    boolean isQuorumPresent(int quorumSize) {
        return getDataMemberCount() >= quorumSize;
    }

    /**
     * Completes the job which is coordinated with the given master context object.
     */
    @CheckReturnValue
    CompletableFuture<Void> completeJob(MasterContext masterContext, long completionTime, Throwable error) {
        return submitToCoordinatorThread(() -> {
            // the order of operations is important.

            long jobId = masterContext.jobId();
            String coordinator = nodeEngine.getNode().getThisUuid();
            jobRepository.completeJob(jobId, coordinator, completionTime, error);
            if (masterContexts.remove(masterContext.jobId(), masterContext)) {
                logger.fine(masterContext.jobIdString() + " is completed");
            } else {
                MasterContext existing = masterContexts.get(jobId);
                if (existing != null) {
                    logger.severe("Different master context found to complete " + masterContext.jobIdString()
                            + ", master context execution " + idToString(existing.executionId()));
                } else {
                    logger.severe("No master context found to complete " + masterContext.jobIdString());
                }
            }
        });
    }

    /**
     * Schedules a restart task that will be run in future for the given job
     */
    void scheduleRestart(long jobId) {
        MasterContext masterContext = masterContexts.get(jobId);
        if (masterContext == null) {
            logger.severe("Master context for job " + idToString(jobId) + " not found to schedule restart");
            return;
        }
        logger.fine("Scheduling restart on master for job " + masterContext.jobName());
        nodeEngine.getExecutionService().schedule(COORDINATOR_EXECUTOR_NAME, () -> restartJob(jobId),
                RETRY_DELAY_IN_MILLIS, MILLISECONDS);
    }

    void scheduleSnapshot(MasterContext mc, long executionId) {
        long snapshotInterval = mc.jobConfig().getSnapshotIntervalMillis();
        InternalExecutionService executionService = nodeEngine.getExecutionService();
        if (logger.isFineEnabled()) {
            logger.fine(mc.jobIdString() + " snapshot is scheduled in " + snapshotInterval + "ms");
        }
        executionService.schedule(COORDINATOR_EXECUTOR_NAME,
                () -> mc.snapshotContext().startScheduledSnapshot(executionId),
                snapshotInterval, MILLISECONDS);
    }

    /**
     * Restarts a job for a new execution if the cluster is stable.
     * Otherwise, it reschedules the restart task.
     */
    void restartJob(long jobId) {
        MasterContext masterContext = masterContexts.get(jobId);
        if (masterContext == null) {
            logger.severe("Master context for job " + idToString(jobId) + " not found to restart");
            return;
        }
        tryStartJob(masterContext);
    }

    private void checkOperationalState() {
        if (isClusterEnteringPassiveState) {
            throw new EnteringPassiveClusterStateException();
        }
    }

    private void scheduleScaleUp(long delay) {
        int counter = scaleUpScheduledCount.incrementAndGet();
        nodeEngine.getExecutionService().schedule(() -> scaleJobsUpNow(counter), delay, MILLISECONDS);
    }

    private void scaleJobsUpNow(int counter) {
        // if another scale-up was scheduled after this one, ignore this one
        if (scaleUpScheduledCount.get() != counter) {
            return;
        }
        // if we can't start jobs yet, we also won't tear them down
        if (!shouldStartJobs()) {
            scheduleScaleUp(RETRY_DELAY_IN_MILLIS);
            return;
        }

        boolean allSucceeded = true;
        int dataMembersCount = nodeEngine.getClusterService().getMembers(DATA_MEMBER_SELECTOR).size();
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        // If the number of partitions is lower than the data member count, some members won't have
        // any partitions assigned. Jet doesn't use such members.
        int dataMembersWithPartitionsCount = Math.min(dataMembersCount, partitionCount);
        for (MasterContext mc : masterContexts.values()) {
            allSucceeded &= mc.jobContext().maybeScaleUp(dataMembersWithPartitionsCount);
        }
        if (!allSucceeded) {
            scheduleScaleUp(RETRY_DELAY_IN_MILLIS);
        }
    }

    /**
     * Scans all job records and updates quorum size of a split-brain protection enabled
     * job with current cluster quorum size if the current cluster quorum size is larger
     */
    private void updateQuorumValues() {
        if (!shouldCheckQuorumValues()) {
            return;
        }

        submitToCoordinatorThread(() -> {
            try {
                int currentQuorumSize = getQuorumSize();
                for (JobRecord jobRecord : jobRepository.getJobRecords()) {
                    try {
                        if (!jobRecord.getConfig().isSplitBrainProtectionEnabled()) {
                            continue;
                        }
                        MasterContext masterContext = masterContexts.get(jobRecord.getJobId());
                        // if MasterContext doesn't exist, update in the IMap directly, using a sync method
                        if (masterContext == null) {
                            jobRepository.updateJobQuorumSizeIfSmaller(jobRecord.getJobId(), currentQuorumSize);
                            // check the master context again, it might have been just created and have picked
                            // up the JobRecord before being updated
                            masterContext = masterContexts.get(jobRecord.getJobId());
                        }
                        if (masterContext != null) {
                            masterContext.updateQuorumSize(currentQuorumSize);
                        }
                    } catch (Exception e) {
                        logger.severe("Quorum of job " + idToString(jobRecord.getJobId())
                                + " could not be updated to " + currentQuorumSize, e);
                    }
                }
            } catch (Exception e) {
                logger.severe("update quorum values task failed", e);
            }
        });
    }

    private boolean shouldCheckQuorumValues() {
        return isMaster() && nodeEngine.isRunning()
                && getInternalPartitionService().getPartitionStateManager().isInitialized();
    }

    private String dagToJson(long jobId, JobConfig jobConfig, Data dagData) {
        ClassLoader classLoader = jetService.getJobExecutionService().getClassLoader(jobConfig, jobId);
        DAG dag = deserializeWithCustomClassLoader(nodeEngine.getSerializationService(), classLoader, dagData);
        int coopThreadCount = getJetInstance(nodeEngine).getConfig().getInstanceConfig().getCooperativeThreadCount();
        return dag.toJson(coopThreadCount).toString();
    }

    private CompletableFuture<Void> startJobIfNotStartedOrCompleted(
            @Nonnull JobRecord jobRecord,
            @Nonnull JobExecutionRecord jobExecutionRecord, String reason
    ) {
        // the order of operations is important.
        long jobId = jobRecord.getJobId();
        JobResult jobResult = jobRepository.getJobResult(jobId);
        if (jobResult != null) {
            logger.fine("Not starting job " + idToString(jobId) + ", already has result: " + jobResult);
            return jobResult.asCompletableFuture();
        }

        MasterContext masterContext;
        MasterContext oldMasterContext;
        synchronized (lock) {
            checkOperationalState();

            masterContext = createMasterContext(jobRecord, jobExecutionRecord);
            oldMasterContext = masterContexts.putIfAbsent(jobId, masterContext);
        }

        if (oldMasterContext != null) {
            return oldMasterContext.jobContext().jobCompletionFuture();
        }

        // If job is not currently running, it might be that it just completed.
        // Since we've put the MasterContext into the masterContexts map, someone else could
        // have joined to the job in the meantime so we should notify its future.
        if (completeMasterContextIfJobAlreadyCompleted(masterContext)) {
            return masterContext.jobContext().jobCompletionFuture();
        }

        if (jobExecutionRecord.isSuspended()) {
            logFinest(logger, "MasterContext for suspended %s is created", masterContext.jobIdString());
        } else {
            logger.info("Starting job " + idToString(jobId) + ": " + reason);
            tryStartJob(masterContext);
        }

        return masterContext.jobContext().jobCompletionFuture();
    }

    // If a job result is present, it completes the master context using the job result
    private boolean completeMasterContextIfJobAlreadyCompleted(MasterContext masterContext) {
        long jobId = masterContext.jobId();
        JobResult jobResult = jobRepository.getJobResult(jobId);
        if (jobResult != null) {
            logger.fine("Completing master context for " + masterContext.jobIdString()
                    + " since already completed with result: " + jobResult);
            masterContext.jobContext().setFinalResult(jobResult.getFailureAsThrowable());
            return masterContexts.remove(jobId, masterContext);
        }

        if (!masterContext.jobConfig().isAutoScaling() && jobRepository.getExecutionIdCount(jobId) > 0) {
            logger.info("Suspending or failing " + masterContext.jobIdString()
                    + " since auto-restart is disabled and the job has been executed before");
            masterContext.jobContext().finalizeJob(new TopologyChangedException());
        }

        return false;
    }

    private void tryStartJob(MasterContext masterContext) {
        masterContext.jobContext().tryStartJob(jobRepository::newExecutionId);
    }

    private int getQuorumSize() {
        return (getDataMemberCount() / 2) + 1;
    }

    private int getDataMemberCount() {
        ClusterService clusterService = nodeEngine.getClusterService();
        return clusterService.getMembers(DATA_MEMBER_SELECTOR).size();
    }

    private JobSummary getJobSummary(JobRecord record) {
        MasterContext ctx = masterContexts.get(record.getJobId());
        long execId = ctx == null ? 0 : ctx.executionId();
        JobStatus status;
        if (ctx == null) {
            JobExecutionRecord executionRecord = jobRepository.getJobExecutionRecord(record.getJobId());
            status = executionRecord != null && executionRecord.isSuspended()
                    ? JobStatus.SUSPENDED : JobStatus.NOT_RUNNING;
        } else {
            status = ctx.jobStatus();
        }
        return new JobSummary(record.getJobId(), execId, record.getJobNameOrId(), status, record.getCreationTime());
    }

    private InternalPartitionServiceImpl getInternalPartitionService() {
        Node node = nodeEngine.getNode();
        return (InternalPartitionServiceImpl) node.getPartitionService();
    }

    // runs periodically to restart jobs on coordinator failure and perform GC
    private void scanJobs() {
        try {
            if (!shouldStartJobs()) {
                return;
            }
            Collection<JobRecord> jobs = jobRepository.getJobRecords();
            for (JobRecord jobRecord : jobs) {
                JobExecutionRecord jobExecutionRecord = ensureExecutionRecord(jobRecord.getJobId(),
                        jobRepository.getJobExecutionRecord(jobRecord.getJobId()));
                startJobIfNotStartedOrCompleted(jobRecord, jobExecutionRecord, "discovered by scanning of JobRecords");
            }
            jobRepository.cleanup(nodeEngine);
            if (!jobsScanned) {
                synchronized (lock) {
                    jobsScanned = true;
                }
            }
        } catch (Exception e) {
            if (e instanceof HazelcastInstanceNotActiveException) {
                return;
            }
            logger.severe("Scanning jobs failed", e);
        }
    }

    private JobExecutionRecord ensureExecutionRecord(long jobId, JobExecutionRecord record) {
        return record != null ? record : new JobExecutionRecord(jobId, getQuorumSize(), false);
    }

    @SuppressWarnings("WeakerAccess") // used by jet-enterprise
    void assertIsMaster(String error) {
        if (!isMaster()) {
            throw new JetException(error + ". Master address: " + nodeEngine.getClusterService().getMasterAddress());
        }
    }

    private boolean isMaster() {
        return nodeEngine.getClusterService().isMaster();
    }

    NodeEngineImpl nodeEngine() {
        return nodeEngine;
    }

    CompletableFuture<Void> submitToCoordinatorThread(Runnable action) {
        return submitToCoordinatorThread(() -> {
            action.run();
            return null;
        });
    }

    private <T> CompletableFuture<T> submitToCoordinatorThread(Callable<T> action) {
        // if we are on our thread already, execute directly in a blocking way
        if (IS_JOB_COORDINATOR_THREAD.get()) {
            try {
                return CompletableFuture.completedFuture(action.call());
            } catch (Throwable e) {
                return com.hazelcast.jet.impl.util.Util.exceptionallyCompletedFuture(e);
            }
        }

        Future<T> future = nodeEngine.getExecutionService().submit(COORDINATOR_EXECUTOR_NAME, () -> {
            assert !IS_JOB_COORDINATOR_THREAD.get() : "flag already raised";
            IS_JOB_COORDINATOR_THREAD.set(true);
            try {
                return action.call();
            } finally {
                IS_JOB_COORDINATOR_THREAD.set(false);
            }
        });
        return Util.toCompletableFuture(nodeEngine.getExecutionService().asCompletableFuture(future));
    }
}
