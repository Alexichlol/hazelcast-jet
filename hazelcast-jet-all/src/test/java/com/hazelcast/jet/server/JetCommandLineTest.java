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

package com.hazelcast.jet.server;

import com.hazelcast.config.EventJournalConfig;
import com.hazelcast.core.IList;
import com.hazelcast.jet.IListJet;
import com.hazelcast.jet.IMapJet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.test.HazelcastParallelClassRunner;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;

import static com.hazelcast.jet.pipeline.JournalInitialPosition.START_FROM_OLDEST;
import static com.hazelcast.jet.server.JetCommandLine.runCommandLine;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
public class JetCommandLineTest extends JetTestSupport {

    private static final String SOURCE_NAME = "source";
    private static final String SINK_NAME = "sink";
    private static final int ITEM_COUNT = 1000;

    private static Path testJobJarFile;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private ByteArrayOutputStream baosOut;
    private ByteArrayOutputStream baosErr;

    private PrintStream out;
    private PrintStream err;
    private JetInstance jet;
    private IMapJet<Integer, Integer> sourceMap;
    private IListJet<Integer> sinkList;
    private JetInstance client;

    @BeforeClass
    public static void beforeClass() throws IOException {
        testJobJarFile = Files.createTempFile("testjob-", ".jar");
        IOUtil.copy(JetCommandLineTest.class.getResourceAsStream("testjob.jar"), testJobJarFile.toFile());
    }

    @AfterClass
    public static void afterClass() {
        IOUtil.deleteQuietly(testJobJarFile.toFile());
    }

    @Before
    public void before() {
        JetConfig cfg = new JetConfig();
        cfg.getHazelcastConfig().addEventJournalConfig(new EventJournalConfig().setMapName(SOURCE_NAME));
        jet = createJetMember(cfg);
        client = createJetClient();
        resetOut();

        sourceMap = jet.getMap(SOURCE_NAME);
        IntStream.range(0, ITEM_COUNT).forEach(i -> sourceMap.put(i, i));
        sinkList = jet.getList(SINK_NAME);
    }

    @After
    public void after() {
        String stdOutput = captureOut();
        if (stdOutput.length() > 0) {
            System.out.println("--- Captured standard output");
            System.out.println(stdOutput);
            System.out.println("--- End of captured standard output");
        }
        String errOutput = captureErr();
        if (errOutput.length() > 0) {
            System.out.println("--- Captured error output");
            System.out.println(errOutput);
            System.out.println("--- End of captured error output");
        }
    }

    @Test
    public void test_listJobs() {
        // Given
        Job job = newJob();

        // When
        run("list-jobs");

        // Then
        String actual = captureOut();
        assertContains(actual, job.getName());
        assertContains(actual, job.getIdString());
        assertContains(actual, job.getStatus().toString());
    }

    @Test
    public void test_cancelJob_byJobName() {
        // Given
        Job job = newJob();

        // When
        run("cancel", job.getName());

        // Then
        assertJobStatusEventually(job, JobStatus.FAILED);
    }

    @Test
    public void test_cancelJob_byJobId() {
        // Given
        Job job = newJob();

        // When
        run("cancel", job.getIdString());

        // Then
        assertJobStatusEventually(job, JobStatus.FAILED);
    }

    @Test
    public void test_cancelJob_invalidNameOrId() {
        // When
        // Then
        exception.expectMessage("No job with name or id 'invalid' was found");
        run("cancel", "invalid");
    }

    @Test
    public void test_cancelJob_jobNotActive() {
        // Given
        Job job = newJob();
        job.cancel();
        assertJobStatusEventually(job, JobStatus.FAILED);

        // When
        // Then
        exception.expectMessage("is not active");
        run("cancel", job.getName());
    }

    @Test
    public void test_suspendJob_byJobName() {
        // Given
        Job job = newJob();

        // When
        run("suspend", job.getName());

        // Then
        assertJobStatusEventually(job, JobStatus.SUSPENDED);
    }

    @Test
    public void test_suspendJob_byJobId() {
        // Given
        Job job = newJob();

        // When
        run("suspend", job.getIdString());

        // Then
        assertJobStatusEventually(job, JobStatus.SUSPENDED);
    }

    @Test
    public void test_suspendJob_invalidNameOrId() {
        // When
        // Then
        exception.expectMessage("No job with name or id 'invalid' was found");
        run("cancel", "invalid");
    }

    @Test
    public void test_suspendJob_jobNotRunning() {
        // Given
        Job job = newJob();
        job.cancel();
        assertJobStatusEventually(job, JobStatus.FAILED);

        // When
        // Then
        exception.expectMessage("is not running");
        run("suspend", job.getName());
    }

    @Test
    public void test_resumeJob_byJobName() {
        // Given
        Job job = newJob();
        assertJobStatusEventually(job, JobStatus.RUNNING);
        job.suspend();
        assertJobStatusEventually(job, JobStatus.SUSPENDED);

        // When
        run("resume", job.getName());

        // Then
        assertJobStatusEventually(job, JobStatus.RUNNING);
    }

    @Test
    public void test_resumeJob_byJobId() {
        // Given
        Job job = newJob();
        assertJobStatusEventually(job, JobStatus.RUNNING);
        job.suspend();
        assertJobStatusEventually(job, JobStatus.SUSPENDED);

        // When
        run("resume", job.getIdString());

        // Then
        assertJobStatusEventually(job, JobStatus.RUNNING);
    }

    @Test
    public void test_resumeJob_invalidNameOrId() {
        // When
        // Then
        exception.expectMessage("No job with name or id 'invalid' was found");
        run("resume", "invalid");
    }

    @Test
    public void test_resumeJob_jobNotSuspended() {
        // Given
        Job job = newJob();
        assertJobStatusEventually(job, JobStatus.RUNNING);

        // When
        // Then
        exception.expectMessage("is not suspended");
        run("resume", job.getName());
    }

    @Test
    public void test_restartJob_byJobName() {
        // Given
        Job job = newJob();
        assertTrueEventually(() -> assertEquals(ITEM_COUNT, sinkList.size()));

        // When
        run("restart", job.getName());

        // Then
        // we expect the same items to be read again due to lack of snapshots
        assertTrueEventually(() -> assertEquals(ITEM_COUNT * 2, sinkList.size()));
    }

    @Test
    public void test_restartJob_byJobId() {
        // Given
        Job job = newJob();
        assertTrueEventually(() -> assertEquals(ITEM_COUNT, sinkList.size()));

        // When
        run("restart", job.getIdString());

        // Then
        // we expect the same items to be read again due to lack of snapshots
        assertTrueEventually(() -> assertEquals(ITEM_COUNT * 2, sinkList.size()));
    }

    @Test
    public void test_restartJob_invalidNameOrId() {
        // When
        // Then
        exception.expectMessage("No job with name or id 'invalid' was found");
        run("restart", "invalid");
    }

    @Test
    public void test_restartJob_jobNotRunning() {
        // Given
        Job job = newJob();
        assertJobStatusEventually(job, JobStatus.RUNNING);
        job.suspend();
        assertJobStatusEventually(job, JobStatus.SUSPENDED);

        // When
        // Then
        exception.expectMessage("is not running");
        run("restart", job.getName());
    }

    @Test
    public void test_saveSnapshot_invalidNameOrId() {
        // When
        // Then
        exception.expectMessage("No job with name or id 'invalid' was found");
        run("save-snapshot", "invalid", "my-snapshot");
    }

    @Test
    public void test_saveSnapshot_jobNotActive() {
        // Given
        Job job = newJob();
        assertJobStatusEventually(job, JobStatus.RUNNING);
        job.cancel();
        assertJobStatusEventually(job, JobStatus.FAILED);

        // When
        // Then
        exception.expectMessage("is not active");
        run("save-snapshot", job.getIdString(), "my-snapshot");
    }

    @Test
    public void test_listSnapshots() {
        // Given
        // When
        run("list-snapshots");

        // Then
        String actual = captureOut();
        assertTrue("output should contain one line (the table header), but contains:\n" + actual,
                actual.trim().indexOf('\n') < 0 && !actual.isEmpty());
    }

    @Test
    public void test_cluster() {
        // When
        run("cluster");

        // Then
        String actual = captureOut();
        assertContains(actual, jet.getCluster().getLocalMember().getUuid());
        assertContains(actual, "ACTIVE");
    }

    @Test
    public void test_verbosity() {
        testVerbosity("cancel", "jobName", "-v");
        testVerbosity("-v", "cancel", "jobName");
        testVerbosity("cluster", "-v");
        testVerbosity("-v", "cluster");
        testVerbosity("delete-snapshot", "snapshotName", "-v");
        testVerbosity("-v", "delete-snapshot", "snapshotName");
        testVerbosity("list-jobs", "-v");
        testVerbosity("-v", "list-jobs", "-v");
        testVerbosity("list-snapshots", "-v");
        testVerbosity("-v", "list-snapshots");
        testVerbosity("restart", "jobName", "-v");
        testVerbosity("-v", "restart", "jobName");
        testVerbosity("resume", "jobName", "-v");
        testVerbosity("-v", "resume", "jobName");
        testVerbosity("save-snapshot", "jobName", "snapshotName", "-v");
        testVerbosity("-v", "save-snapshot", "jobName", "snapshotName");
        testVerbosity("submit", "-v", "job.jar");
        testVerbosity("-v", "submit", "job.jar");
        testVerbosity("suspend", "jobName", "-v");
        testVerbosity("-v", "suspend", "jobName");
    }

    @Test
    public void test_submit() {
        run("submit", testJobJarFile.toString());
        assertTrueEventually(() -> assertEquals(1, jet.getJobs().size()));
        Job job = jet.getJobs().get(0);
        assertJobStatusEventually(job, JobStatus.RUNNING);
        assertNull(job.getName());
    }

    @Test
    public void test_submit_clientShutdownWhenDone() {
        run("submit", testJobJarFile.toString());
        assertTrueEventually(() -> assertEquals(1, jet.getJobs().size()));
        Job job = jet.getJobs().get(0);
        assertJobStatusEventually(job, JobStatus.RUNNING);
        assertFalse("Instance should be shut down", client.getHazelcastInstance().getLifecycleService().isRunning());
    }

    @Test
    public void test_submit_nameUsed() {
        run("submit", "-n", "fooName", testJobJarFile.toString());
        assertTrueEventually(() -> assertEquals(1, jet.getJobs().size()), 5);
        Job job = jet.getJobs().get(0);
        assertEquals("fooName", job.getName());
    }

    @Test
    public void test_submit_argsPassing() {
        run("submit", testJobJarFile.toString(), "--jobOption", "fooValue");
        // this list is created by the job in testjob.jar
        IList<String> args = jet.getList("args");
        assertTrueEventually(() -> assertContains(captureOut(), " with arguments [--jobOption, fooValue]"));
    }

    private void testVerbosity(String... args) {
        System.out.println("Testing verbosity with parameters " + Arrays.toString(args));
        try {
            resetOut();
            run(args);
        } catch (Exception ignored) {
        }
        assertContains(captureOut(), "Verbose mode is on");
    }

    private Job newJob() {
        Pipeline p = Pipeline.create();
        p.drawFrom(Sources.mapJournal(SOURCE_NAME, START_FROM_OLDEST))
                .withoutTimestamps()
                .drainTo(Sinks.list(SINK_NAME));
        Job job = jet.newJob(p, new JobConfig().setName("job-infinite-pipeline"));
        assertJobStatusEventually(job, JobStatus.RUNNING);
        return job;
    }

    private void run(String... args) {
        runCommandLine(cfg -> client, out, err, false, args);
    }

    private void resetOut() {
        baosOut = new ByteArrayOutputStream();
        baosErr = new ByteArrayOutputStream();
        out = new PrintStream(baosOut);
        err = new PrintStream(baosErr);
    }

    private String captureOut() {
        out.flush();
        return new String(baosOut.toByteArray());
    }

    private String captureErr() {
        err.flush();
        return new String(baosErr.toByteArray());
    }
}
