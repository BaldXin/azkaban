/*
 * Copyright 2017 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.execapp;

import static org.mockito.Mockito.mock;

import azkaban.execapp.event.FlowWatcher;
import azkaban.execapp.event.LocalFlowWatcher;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableFlowBase;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutionOptions;
import azkaban.executor.ExecutorLoader;
import azkaban.executor.InteractiveTestJob;
import azkaban.executor.JavaJob;
import azkaban.executor.MockExecutorLoader;
import azkaban.executor.Status;
import azkaban.flow.Flow;
import azkaban.jobtype.JobTypeManager;
import azkaban.jobtype.JobTypePluginSet;
import azkaban.project.Project;
import azkaban.project.ProjectLoader;
import azkaban.spi.AzkabanEventReporter;
import azkaban.utils.Props;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Flows in this test: joba jobb joba1 jobc->joba jobd->joba jobe->jobb,jobc,jobd jobf->jobe,joba1
 *
 * jobb = innerFlow innerJobA innerJobB->innerJobA innerJobC->innerJobB
 * innerFlow->innerJobB,innerJobC
 *
 * jobd=innerFlow2 innerFlow2->innerJobA
 *
 * @author rpark
 */
public class FlowRunnerPipelineTest {

  private static int id = 101;
  private final Logger logger = Logger.getLogger(FlowRunnerTest2.class);
  private final AzkabanEventReporter azkabanEventReporter =
      EventReporterUtil.getTestAzkabanEventReporter();
  private File workingDir;
  private JobTypeManager jobtypeManager;
  private ExecutorLoader fakeExecutorLoader;
  private Project project;
  private Map<String, Flow> flowMap;

  public FlowRunnerPipelineTest() {
  }

  @Before
  public void setUp() throws Exception {
    System.out.println("Create temp dir");
    this.workingDir = new File("_AzkabanTestDir_" + System.currentTimeMillis());
    if (this.workingDir.exists()) {
      FileUtils.deleteDirectory(this.workingDir);
    }
    this.workingDir.mkdirs();
    this.jobtypeManager =
        new JobTypeManager(null, null, this.getClass().getClassLoader());
    final JobTypePluginSet pluginSet = this.jobtypeManager.getJobTypePluginSet();

    pluginSet.addPluginClass("java", JavaJob.class);
    pluginSet.addPluginClass("test", InteractiveTestJob.class);
    this.fakeExecutorLoader = new MockExecutorLoader();
    this.project = new Project(1, "testProject");

    final File dir = new File("unit/executions/embedded2");
    this.flowMap = FlowRunnerTestUtil
        .prepareProject(this.project, dir, this.logger, this.workingDir);

    InteractiveTestJob.clearTestJobs();
  }

  @After
  public void tearDown() throws IOException {
    System.out.println("Teardown temp dir");
    if (this.workingDir != null) {
      FileUtils.deleteDirectory(this.workingDir);
      this.workingDir = null;
    }
  }

  @Ignore
  @Test
  public void testBasicPipelineLevel1Run() throws Exception {
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final FlowRunner previousRunner =
        createFlowRunner(eventCollector, "jobf", "prev");

    final ExecutionOptions options = new ExecutionOptions();
    options.setPipelineExecutionId(previousRunner.getExecutableFlow()
        .getExecutionId());
    options.setPipelineLevel(1);
    final FlowWatcher watcher = new LocalFlowWatcher(previousRunner);
    final FlowRunner pipelineRunner =
        createFlowRunner(eventCollector, "jobf", "pipe", options);
    pipelineRunner.setFlowWatcher(watcher);

    final Map<String, Status> previousExpectedStateMap =
        new HashMap<>();
    final Map<String, Status> pipelineExpectedStateMap =
        new HashMap<>();
    final Map<String, ExecutableNode> previousNodeMap =
        new HashMap<>();
    final Map<String, ExecutableNode> pipelineNodeMap =
        new HashMap<>();

    // 1. START FLOW
    final ExecutableFlow pipelineFlow = pipelineRunner.getExecutableFlow();
    final ExecutableFlow previousFlow = previousRunner.getExecutableFlow();
    createExpectedStateMap(previousFlow, previousExpectedStateMap,
        previousNodeMap);
    createExpectedStateMap(pipelineFlow, pipelineExpectedStateMap,
        pipelineNodeMap);

    final Thread thread1 = runFlowRunnerInThread(previousRunner);
    pause(250);
    final Thread thread2 = runFlowRunnerInThread(pipelineRunner);
    pause(500);

    previousExpectedStateMap.put("joba", Status.RUNNING);
    previousExpectedStateMap.put("joba1", Status.RUNNING);
    pipelineExpectedStateMap.put("joba", Status.QUEUED);
    pipelineExpectedStateMap.put("joba1", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:joba").succeedJob();
    pause(250);
    previousExpectedStateMap.put("joba", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobb", Status.RUNNING);
    previousExpectedStateMap.put("jobb:innerJobA", Status.RUNNING);
    previousExpectedStateMap.put("jobd", Status.RUNNING);
    previousExpectedStateMap.put("jobc", Status.RUNNING);
    previousExpectedStateMap.put("jobd:innerJobA", Status.RUNNING);
    pipelineExpectedStateMap.put("joba", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:jobb:innerJobA").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobb:innerJobA", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobb:innerJobB", Status.RUNNING);
    previousExpectedStateMap.put("jobb:innerJobC", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:joba").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("joba", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb", Status.RUNNING);
    pipelineExpectedStateMap.put("jobd", Status.RUNNING);
    pipelineExpectedStateMap.put("jobc", Status.QUEUED);
    pipelineExpectedStateMap.put("jobd:innerJobA", Status.QUEUED);
    pipelineExpectedStateMap.put("jobb:innerJobA", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:jobd:innerJobA").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobd:innerJobA", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobd:innerFlow2", Status.RUNNING);
    pipelineExpectedStateMap.put("jobd:innerJobA", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    // Finish the previous d side
    InteractiveTestJob.getTestJob("prev:jobd:innerFlow2").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobd:innerFlow2", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobd", Status.SUCCEEDED);
    compareStates(previousExpectedStateMap, previousNodeMap);

    InteractiveTestJob.getTestJob("prev:jobb:innerJobB").succeedJob();
    InteractiveTestJob.getTestJob("prev:jobb:innerJobC").succeedJob();
    InteractiveTestJob.getTestJob("prev:jobc").succeedJob();
    pause(250);
    InteractiveTestJob.getTestJob("pipe:jobb:innerJobA").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobb:innerJobB", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobb:innerJobC", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobb:innerFlow", Status.RUNNING);
    previousExpectedStateMap.put("jobc", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb:innerJobA", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobc", Status.RUNNING);
    pipelineExpectedStateMap.put("jobb:innerJobB", Status.RUNNING);
    pipelineExpectedStateMap.put("jobb:innerJobC", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:jobb:innerFlow").succeedJob();
    InteractiveTestJob.getTestJob("pipe:jobc").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobb:innerFlow", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobb", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobe", Status.RUNNING);
    pipelineExpectedStateMap.put("jobc", Status.SUCCEEDED);

    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:jobb:innerJobB").succeedJob();
    InteractiveTestJob.getTestJob("pipe:jobb:innerJobC").succeedJob();
    InteractiveTestJob.getTestJob("prev:jobe").succeedJob();
    pause(250);
    previousExpectedStateMap.put("jobe", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb:innerJobB", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb:innerJobC", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb:innerFlow", Status.RUNNING);

    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:jobd:innerJobA").succeedJob();
    InteractiveTestJob.getTestJob("pipe:jobb:innerFlow").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("jobb", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobd:innerJobA", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobb:innerFlow", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobd:innerFlow2", Status.RUNNING);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:jobd:innerFlow2").succeedJob();
    InteractiveTestJob.getTestJob("prev:joba1").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("jobd:innerFlow2", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobd", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobf", Status.RUNNING);
    previousExpectedStateMap.put("joba1", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("joba1", Status.RUNNING);
    pipelineExpectedStateMap.put("jobe", Status.RUNNING);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);
    compareStates(previousExpectedStateMap, previousNodeMap);

    InteractiveTestJob.getTestJob("pipe:jobe").succeedJob();
    InteractiveTestJob.getTestJob("prev:jobf").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("jobe", Status.SUCCEEDED);
    previousExpectedStateMap.put("jobf", Status.SUCCEEDED);
    Assert.assertEquals(Status.SUCCEEDED, previousFlow.getStatus());
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:joba1").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("joba1", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("jobf", Status.RUNNING);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:jobf").succeedJob();
    pause(250);
    Assert.assertEquals(Status.SUCCEEDED, pipelineFlow.getStatus());
    Assert.assertFalse(thread1.isAlive());
    Assert.assertFalse(thread2.isAlive());
  }

  @Ignore
  @Test
  public void testBasicPipelineLevel2Run() throws Exception {
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final FlowRunner previousRunner =
        createFlowRunner(eventCollector, "pipelineFlow", "prev");

    final ExecutionOptions options = new ExecutionOptions();
    options.setPipelineExecutionId(previousRunner.getExecutableFlow()
        .getExecutionId());
    options.setPipelineLevel(2);
    final FlowWatcher watcher = new LocalFlowWatcher(previousRunner);
    final FlowRunner pipelineRunner =
        createFlowRunner(eventCollector, "pipelineFlow", "pipe", options);
    pipelineRunner.setFlowWatcher(watcher);

    final Map<String, Status> previousExpectedStateMap =
        new HashMap<>();
    final Map<String, Status> pipelineExpectedStateMap =
        new HashMap<>();
    final Map<String, ExecutableNode> previousNodeMap =
        new HashMap<>();
    final Map<String, ExecutableNode> pipelineNodeMap =
        new HashMap<>();

    // 1. START FLOW
    final ExecutableFlow pipelineFlow = pipelineRunner.getExecutableFlow();
    final ExecutableFlow previousFlow = previousRunner.getExecutableFlow();
    createExpectedStateMap(previousFlow, previousExpectedStateMap,
        previousNodeMap);
    createExpectedStateMap(pipelineFlow, pipelineExpectedStateMap,
        pipelineNodeMap);

    final Thread thread1 = runFlowRunnerInThread(previousRunner);
    pause(250);
    final Thread thread2 = runFlowRunnerInThread(pipelineRunner);
    pause(250);

    previousExpectedStateMap.put("pipeline1", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline1").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline1", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline2", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline2").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline2", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3", Status.RUNNING);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobA",
        Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline2", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipelineEmbeddedFlow3:innerJobA")
        .succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobA",
        Status.SUCCEEDED);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobB",
        Status.RUNNING);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobC",
        Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline2", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline2").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline2", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3", Status.RUNNING);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobA",
        Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipelineEmbeddedFlow3:innerJobB")
        .succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobB",
        Status.SUCCEEDED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipelineEmbeddedFlow3:innerJobC")
        .succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.RUNNING);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobC",
        Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobA",
        Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipelineEmbeddedFlow3:innerJobA")
        .succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobA",
        Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobC",
        Status.QUEUED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobB",
        Status.QUEUED);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipelineEmbeddedFlow3:innerFlow")
        .succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.SUCCEEDED);
    previousExpectedStateMap.put("pipelineEmbeddedFlow3", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline4", Status.RUNNING);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobC",
        Status.RUNNING);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobB",
        Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipelineEmbeddedFlow3:innerJobB")
        .succeedJob();
    InteractiveTestJob.getTestJob("pipe:pipelineEmbeddedFlow3:innerJobC")
        .succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobC",
        Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerJobB",
        Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline4").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline4", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipelineFlow", Status.RUNNING);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipelineFlow").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipelineFlow", Status.SUCCEEDED);
    Assert.assertEquals(Status.SUCCEEDED, previousFlow.getStatus());
    Assert.assertFalse(thread1.isAlive());
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipelineEmbeddedFlow3:innerFlow")
        .succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3:innerFlow",
        Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineEmbeddedFlow3", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline4", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline4").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline4", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipelineFlow", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipelineFlow").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipelineFlow", Status.SUCCEEDED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);
    Assert.assertEquals(Status.SUCCEEDED, pipelineFlow.getStatus());
    Assert.assertFalse(thread2.isAlive());
  }

  @Ignore
  @Test
  public void testBasicPipelineLevel2Run2() throws Exception {
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final FlowRunner previousRunner =
        createFlowRunner(eventCollector, "pipeline1_2", "prev");

    final ExecutionOptions options = new ExecutionOptions();
    options.setPipelineExecutionId(previousRunner.getExecutableFlow()
        .getExecutionId());
    options.setPipelineLevel(2);
    final FlowWatcher watcher = new LocalFlowWatcher(previousRunner);
    final FlowRunner pipelineRunner =
        createFlowRunner(eventCollector, "pipeline1_2", "pipe", options);
    pipelineRunner.setFlowWatcher(watcher);

    final Map<String, Status> previousExpectedStateMap =
        new HashMap<>();
    final Map<String, Status> pipelineExpectedStateMap =
        new HashMap<>();
    final Map<String, ExecutableNode> previousNodeMap =
        new HashMap<>();
    final Map<String, ExecutableNode> pipelineNodeMap =
        new HashMap<>();

    // 1. START FLOW
    final ExecutableFlow pipelineFlow = pipelineRunner.getExecutableFlow();
    final ExecutableFlow previousFlow = previousRunner.getExecutableFlow();
    createExpectedStateMap(previousFlow, previousExpectedStateMap,
        previousNodeMap);
    createExpectedStateMap(pipelineFlow, pipelineExpectedStateMap,
        pipelineNodeMap);

    final Thread thread1 = runFlowRunnerInThread(previousRunner);
    pause(250);
    final Thread thread2 = runFlowRunnerInThread(pipelineRunner);
    pause(250);

    previousExpectedStateMap.put("pipeline1_1", Status.RUNNING);
    previousExpectedStateMap.put("pipeline1_1:innerJobA", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_1", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_1:innerJobA", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline1_1:innerJobA").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline1_1:innerJobA", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline1_1:innerFlow2", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline1_1:innerFlow2").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline1_1", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline1_1:innerFlow2", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline1_2", Status.RUNNING);
    previousExpectedStateMap.put("pipeline1_2:innerJobA", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_1:innerJobA", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1_1:innerJobA").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1_1:innerJobA", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_1:innerFlow2", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline1_2:innerJobA").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline1_2:innerJobA", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline1_2:innerFlow2", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_1:innerFlow2", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1_1:innerFlow2").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1_1", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_1:innerFlow2", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_2", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_2:innerJobA", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1_1:innerFlow2").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1_1", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_1:innerFlow2", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_2", Status.RUNNING);
    pipelineExpectedStateMap.put("pipeline1_2:innerJobA", Status.QUEUED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("prev:pipeline1_2:innerFlow2").succeedJob();
    pause(250);
    previousExpectedStateMap.put("pipeline1_2:innerFlow2", Status.SUCCEEDED);
    previousExpectedStateMap.put("pipeline1_2", Status.SUCCEEDED);
    Assert.assertEquals(Status.SUCCEEDED, previousFlow.getStatus());
    Assert.assertFalse(thread1.isAlive());
    pipelineExpectedStateMap.put("pipeline1_2:innerJobA", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1_2:innerJobA").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1_2:innerJobA", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_2:innerFlow2", Status.RUNNING);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);

    InteractiveTestJob.getTestJob("pipe:pipeline1_2:innerFlow2").succeedJob();
    pause(250);
    pipelineExpectedStateMap.put("pipeline1_2", Status.SUCCEEDED);
    pipelineExpectedStateMap.put("pipeline1_2:innerFlow2", Status.SUCCEEDED);
    compareStates(previousExpectedStateMap, previousNodeMap);
    compareStates(pipelineExpectedStateMap, pipelineNodeMap);
    Assert.assertEquals(Status.SUCCEEDED, pipelineFlow.getStatus());
    Assert.assertFalse(thread2.isAlive());
  }

  private Thread runFlowRunnerInThread(final FlowRunner runner) {
    final Thread thread = new Thread(runner);
    thread.start();
    return thread;
  }

  private void pause(final long millisec) {
    try {
      Thread.sleep(millisec);
    } catch (final InterruptedException e) {
    }
  }

  private void createExpectedStateMap(final ExecutableFlowBase flow,
      final Map<String, Status> expectedStateMap, final Map<String, ExecutableNode> nodeMap) {
    for (final ExecutableNode node : flow.getExecutableNodes()) {
      expectedStateMap.put(node.getNestedId(), node.getStatus());
      nodeMap.put(node.getNestedId(), node);

      if (node instanceof ExecutableFlowBase) {
        createExpectedStateMap((ExecutableFlowBase) node, expectedStateMap,
            nodeMap);
      }
    }
  }

  private void compareStates(final Map<String, Status> expectedStateMap,
      final Map<String, ExecutableNode> nodeMap) {
    for (final String printedId : expectedStateMap.keySet()) {
      final Status expectedStatus = expectedStateMap.get(printedId);
      final ExecutableNode node = nodeMap.get(printedId);
      if (node == null) {
        System.out.println("id node: " + printedId + " doesn't exist.");
      }
      if (expectedStatus != node.getStatus()) {
        Assert.fail("Expected values do not match for " + printedId
            + ". Expected " + expectedStatus + ", instead received "
            + node.getStatus());
      }
    }
  }

  // private void printCurrentState(String prefix, ExecutableFlowBase flow) {
  //   for  (ExecutableNode node: flow.getExecutableNodes()) {
  //     System.err.println(prefix + node.getNestedId() + "->" +
  //         node.getStatus().name());
  //     if (node instanceof ExecutableFlowBase) {
  //       printCurrentState(prefix, (ExecutableFlowBase)node);
  //     }
  //   }
  // }
  //
  private FlowRunner createFlowRunner(final EventCollectorListener eventCollector,
      final String flowName, final String groupName) throws Exception {
    return createFlowRunner(eventCollector, flowName, groupName,
        new ExecutionOptions(), new Props());
  }

  private FlowRunner createFlowRunner(final EventCollectorListener eventCollector,
      final String flowName, final String groupName, final ExecutionOptions options)
      throws Exception {
    return createFlowRunner(eventCollector, flowName, groupName,
        options, new Props());
  }

  private FlowRunner createFlowRunner(final EventCollectorListener eventCollector,
      final String flowName, final String groupName, final ExecutionOptions options,
      final Props azkabanProps)
      throws Exception {
    final Flow flow = this.flowMap.get(flowName);

    final int exId = id++;
    final ExecutableFlow exFlow = new ExecutableFlow(this.project, flow);
    exFlow.setExecutionPath(this.workingDir.getPath());
    exFlow.setExecutionId(exId);

    final Map<String, String> flowParam = new HashMap<>();
    flowParam.put("group", groupName);
    options.addAllFlowParameters(flowParam);
    exFlow.setExecutionOptions(options);
    this.fakeExecutorLoader.uploadExecutableFlow(exFlow);

    final FlowRunner runner =
        new FlowRunner(this.fakeExecutorLoader.fetchExecutableFlow(exId),
            this.fakeExecutorLoader, mock(ProjectLoader.class), this.jobtypeManager, azkabanProps,
            this.azkabanEventReporter);
    runner.addListener(eventCollector);

    return runner;
  }

}
