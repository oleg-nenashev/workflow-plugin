/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.steps;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.User;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Launcher;
import hudson.remoting.Which;
import hudson.security.ACL;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import jenkins.model.Jenkins;
import org.apache.tools.ant.util.JavaEnvUtils;
import org.jenkinsci.plugins.workflow.SingleJobTestBase;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowListener;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStep;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TestExtension;

/** Tests pertaining to {@code node} and {@code sh} steps. */
public class ExecutorStepTest extends SingleJobTestBase {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Executes a shell script build on a slave.
     *
     * This ensures that the context variable overrides are working as expected, and
     * that they are persisted and resurrected.
     */
    @Test public void buildShellScriptOnSlave() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = createSlave(story.j);
                s.setLabelString("remote quick");
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    sh('echo before=`basename $PWD`')\n" +
                    "    sh('echo ONSLAVE=$ONSLAVE')\n" +
                    "    semaphore 'wait'\n" +
                    "    sh('echo after=$PWD')\n" +
                    "}"));

                startBuilding();
                SemaphoreStep.waitForStart("wait/1", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);
                assertThatWorkflowIsSuspended();
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));

                story.j.assertLogContains("before=demo", b);
                story.j.assertLogContains("ONSLAVE=true", b);

                FlowGraphWalker walker = new FlowGraphWalker(e);
                List<WorkspaceAction> actions = new ArrayList<WorkspaceAction>();
                for (FlowNode n : walker) {
                    WorkspaceAction a = n.getAction(WorkspaceAction.class);
                    if (a != null) {
                        actions.add(a);
                    }
                }
                assertEquals(1, actions.size());
                assertEquals(new HashSet<LabelAtom>(Arrays.asList(LabelAtom.get("remote"), LabelAtom.get("quick"))), actions.get(0).getLabels());
            }
        });
    }

    @Test public void buildShellScriptOnSlaveWithDifferentResumePoint() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                p = jenkins().createProject(WorkflowJob.class, "demo");
                String script = "node {semaphore 'wait'}";
                p.setDefinition(new CpsFlowDefinition(script));
                startBuilding();
                waitForWorkflowToSuspend();
                // intentionally not waiting for semaphore step to begin
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }

    private static Process jnlpProc;
    private void startJnlpProc() throws Exception {
        killJnlpProc();
        ProcessBuilder pb = new ProcessBuilder(JavaEnvUtils.getJreExecutable("java"), "-jar", Which.jarFile(Launcher.class).getAbsolutePath(), "-jnlpUrl", story.j.getURL() + "computer/dumbo/slave-agent.jnlp");
        try {
            ProcessBuilder.class.getMethod("inheritIO").invoke(pb);
        } catch (NoSuchMethodException x) {
            // prior to Java 7
        }
        System.err.println("Running: " + pb.command());
        jnlpProc = pb.start();
    }
    // TODO @After does not seem to work at all in RestartableJenkinsRule
    @AfterClass public static void killJnlpProc() {
        if (jnlpProc != null) {
            jnlpProc.destroy();
            jnlpProc = null;
        }
    }

    @Test public void buildShellScriptAcrossRestart() throws Exception {
        story.addStep(new Statement() {
            @SuppressWarnings("SleepWhileInLoop")
            @Override public void evaluate() throws Throwable {
                Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());
                LOGGER.setLevel(Level.FINE);
                Handler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                LOGGER.addHandler(handler);
                // Cannot use regular JenkinsRule.createSlave due to JENKINS-26398.
                // Nor can we can use JenkinsRule.createComputerLauncher, since spawned commands are killed by CommandLauncher somehow (it is not clear how; apparently before its onClosed kills them off).
                DumbSlave s = new DumbSlave("dumbo", "dummy", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "", new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                story.j.jenkins.addNode(s);
                startJnlpProc();
                p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}"));
                startBuilding();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                assertTrue(b.isBuilding());
                killJnlpProc();
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);
                assertTrue(b.isBuilding()); // TODO occasionally fails; log ends with: ‘Running: Allocate node : Body : Start’ (no shell step in sight)
                startJnlpProc(); // Have to relaunch JNLP agent, since the Jenkins port has changed, and we cannot force JenkinsRule to reuse the same port as before.
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("finished waiting", b);
                story.j.assertLogContains("OK, done", b);
                killJnlpProc();
            }
        });
    }

    @Test public void buildShellScriptAcrossDisconnect() throws Exception {
        story.addStep(new Statement() {
            @SuppressWarnings("SleepWhileInLoop")
            @Override public void evaluate() throws Throwable {
                Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());
                LOGGER.setLevel(Level.FINE);
                Handler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                LOGGER.addHandler(handler);
                DumbSlave s = new DumbSlave("dumbo", "dummy", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "", new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                story.j.jenkins.addNode(s);
                startJnlpProc();
                p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}"));
                startBuilding();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                assertTrue(b.isBuilding());
                Computer c = s.toComputer();
                assertNotNull(c);
                killJnlpProc();
                while (c.isOnline()) {
                    Thread.sleep(100);
                }
                startJnlpProc();
                while (c.isOffline()) {
                    Thread.sleep(100);
                }
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("finished waiting", b); // TODO sometimes is not printed to log, despite f2 having been removed
                story.j.assertLogContains("OK, done", b);
                killJnlpProc();
            }
        });
    }

    @Test public void buildShellScriptQuick() throws Exception {
        final AtomicReference<String> dir = new AtomicReference<String>();
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = createSlave(story.j);
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                p = jenkins().createProject(WorkflowJob.class, "demo");
                dir.set(s.getRemoteFS() + "/workspace/" + p.getFullName());
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    sh('pwd; echo ONSLAVE=$ONSLAVE')\n" +
                    "}"));

                startBuilding();

                while (!e.isComplete()) {
                    e.waitForSuspension();
                }

                assertBuildCompletedSuccessfully();

                story.j.assertLogContains(dir.get(), b);
                story.j.assertLogContains("ONSLAVE=true", b);
            }
        });
    }

    @Test public void acquireWorkspace() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                @SuppressWarnings("deprecation")
                String slaveRoot = story.j.createTmpDir().getPath();
                jenkins().addNode(new DumbSlave("slave", "dummy", slaveRoot, "2", Node.Mode.NORMAL, "", story.j.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList()));
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                        "node('slave') {\n" + // this locks the WS
                        "    sh('echo default=`basename $PWD`')\n" +
                        "    ws {\n" + // and this locks a second one
                        "        sh('echo before=`basename $PWD`')\n" +
                        "        semaphore 'wait'\n" +
                        "        sh('echo after=`basename $PWD`')\n" +
                        "    }\n" +
                        "}"
                ));
                p.save();
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b1);
                assertTrue(b1.isBuilding());
                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/2", b2);
                assertTrue(b2.isBuilding());
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);
                WorkflowRun b1 = p.getBuildByNumber(1);
                CpsFlowExecution e1 = (CpsFlowExecution) b1.getExecution();
                assertThatWorkflowIsSuspended(b1, e1);
                WorkflowRun b2 = p.getBuildByNumber(2);
                CpsFlowExecution e2 = (CpsFlowExecution) b2.getExecution();
                assertThatWorkflowIsSuspended(b2, e2);
                SemaphoreStep.success("wait/1", null);
                SemaphoreStep.success("wait/2", null);
                story.j.waitUntilNoActivity();
                assertBuildCompletedSuccessfully(b1);
                assertBuildCompletedSuccessfully(b2);
                // TODO once got ‘InvalidClassException: cannot bind non-proxy descriptor to a proxy class’ inside BourneShellScript.doLaunch
                story.j.assertLogContains("default=demo", b1);
                story.j.assertLogContains("before=demo@2", b1);
                story.j.assertLogContains("after=demo@2", b1);
                story.j.assertLogContains("default=demo@3", b2);
                story.j.assertLogContains("before=demo@4", b2);
                story.j.assertLogContains("after=demo@4", b2);
                SemaphoreStep.success("wait/3", null);
                WorkflowRun b3 = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                story.j.assertLogContains("default=demo", b3);
                story.j.assertLogContains("before=demo@2", b3);
                story.j.assertLogContains("after=demo@2", b3);
            }
        });
    }

    @Issue("JENKINS-26513")
    @Test public void executorStepRestart() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('special') {echo 'OK ran'}"));
                startBuilding();
                story.j.waitForMessage("Still waiting to schedule task", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                story.j.createSlave("special", null);
                rebuildContext(story.j);
                // TODO JENKINS-27532 sometimes two copies of the WorkflowRun are loaded
                story.j.assertLogContains("OK ran", story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
            }
        });
    }

    @Test public void tailCall() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("def r = node {'the result'}; echo \"got ${r}\""));
                story.j.assertLogContains("got the result", story.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
                p.setDefinition(new CpsFlowDefinition("try {node {error 'a problem'}} catch (e) {echo \"failed with ${e.message}\"}"));
                story.j.assertLogContains("failed with a problem", story.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
            }
        });
    }

    @Issue("JENKINS-31649")
    @Test public void queueTaskVisibility() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                jenkins().setSecurityRealm(story.j.createDummySecurityRealm());
                GlobalMatrixAuthorizationStrategy gmas = new GlobalMatrixAuthorizationStrategy();
                gmas.add(Jenkins.ADMINISTER, "admin");
                jenkins().setAuthorizationStrategy(gmas);
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('nonexistent') {}", true));
                startBuilding();
                story.j.waitForMessage("Still waiting to schedule task", b);
                ACL.impersonate(User.get("admin").impersonate(), new Runnable() {
                    @Override public void run() {
                        Queue.Item[] items = Queue.getInstance().getItems();
                        assertEquals(1, items.length); // fails in 1.638
                        assertEquals(p, items[0].task.getOwnerTask());
                    }
                });
                /* TODO uncomment when on 1.639+, or 1.625.3 if JENKINS-31649 is backported:
                ACL.impersonate(User.get("devel").impersonate(), new Runnable() {
                    @Override public void run() {
                        Queue.Item[] items = Queue.getInstance().getItems();
                        assertEquals(0, items.length); // fails in 1.609.2
                    }
                });
                */
                // TODO this would be a good time to add a third user with READ but no CANCEL permission and check behavior
            }
        });
    }
    
    /**
     * Ensures that {@link FlowListener} callback is being invoked for the {@link ExecutorStepExecution}.
     * It allows to record the executor, which is being utilized by the flow.
     */
    @Test
    @Issue("JENKINS-32225")
    public void shouldInvokeFlowListenerCallbacksAndRecordNodes() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = createSlave(story.j);
                s.setLabelString("remote quick");
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                // Create a project invoking the node() operation to acquire an executor
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    echo 'Hello'\n" +
                    "}"));

                // Run build and wait for its completion
                story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));

                // Get the node invocation monitor
                NodeInvocationListener listener = story.j.jenkins.getExtensionList(FlowListener.class)
                        .get(NodeInvocationListener.class);
                assertThat("NodeInvocationListener must be specified in the test", listener, notNullValue());
                Node builtOn = listener.getBuiltOn();
                assertThat("Node has not been recorded", builtOn, notNullValue());
                assertThat("Node name differs from the expected one", builtOn.getNodeName(), equalTo(s.getNodeName()));
            }
        });
    }
    
    /**
     * Test extension, which locates the last usage of node declared by {@link ExecutorStep}.
     */
    @TestExtension("shouldInvokeFlowListenerCallbacksAndRecordNodes")
    public static class NodeInvocationListener extends FlowListener {
    
        private Node builtOn;

        @CheckForNull
        public Node getBuiltOn() {
            return builtOn;
        }
        
        @Override
        public void onNewHead(FlowNode node) { 
            // We try to catch nodes, which indicate the start of the node() execution
            if (!(node instanceof StepStartNode)) {
                return;
            }
            
            List<FlowNode> parents = node.getParents();
            assertThat("Got a StepStartNode without any parents. It should never happen", 
                    parents.size(), not(equalTo(0)));
            
            FlowNode firstParent = parents.get(0);
            if (firstParent instanceof StepStartNode) { // Parent may be an ExecutorStep
                StepStartNode candidate = (StepStartNode)firstParent;
                if (candidate.getDescriptor() instanceof ExecutorStep.DescriptorImpl) {
                    // We found ExecutorStep => we are within the node() call, which has been initialized
                    Executor found = ExecutorStepExecution.findExecutor(candidate.getExecution());
                    
                    // Record the discovered node
                    builtOn = found != null ? found.getOwner().getNode() : null;
                }
            }
        }
    }
}
