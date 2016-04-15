/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
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

package org.jenkinsci.plugins.cloudstats;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.ManagementLink;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Statistics of past cloud activities.
 */
public class CloudStatistics extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(CloudStatistics.class.getName());

    @Extension @Restricted(NoExternalUse.class)
    public static final CloudStatistics stats = new CloudStatistics();
    @Extension @Restricted(NoExternalUse.class)
    public static final ProvisioningListener pl = new ProvisioningListener(stats);
    @Extension @Restricted(NoExternalUse.class)
    public static final OperationListener ol = new OperationListener(stats);
    @Extension @Restricted(NoExternalUse.class)
    public static final SlaveCompletionDetector scd = new SlaveCompletionDetector(stats);

    /*
     * The log itself uses synchronized collection, to manipulate single entry it needs to be explicitly synchronized.
     */
    private final @Nonnull CyclicThreadSafeCollection<ProvisioningActivity> log = new CyclicThreadSafeCollection<>(100);

    /**
     * Get the singleton instance.
     */
    public static @Nonnull CloudStatistics get() {
        return stats;
    }

    public String getDisplayName() {
        return isActive() ? "Cloud Statistics" : null ;
    }

    @Override
    public String getIconFileName() {
        return isActive() ? "graph.png" : null;
    }

    @Override
    public String getUrlName() {
        return "cloud-stats";
    }

    @Override
    public String getDescription() {
        return "Report of current and past provisioning activities";
    }

    public boolean isActive() {
        return !Jenkins.getInstance().clouds.isEmpty();
    }

    public List<ProvisioningActivity> getActivities() {
        return log.toList();
    }

    public static class ProvisioningListener extends CloudProvisioningListener {

        private final CloudStatistics stats;

        public ProvisioningListener(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override @Restricted(DoNotUse.class)
        public void onStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
                ProvisioningActivity.Id id = getIdFor(plannedNode);
                if (id != null) {
                    onStarted(id);
                }
            }
        }

        /**
         * Inform plugin provisioning has started. This is only needed when provisioned outside {@link NodeProvisioner}.
         */
        public @Nonnull ProvisioningActivity onStarted(@Nonnull ProvisioningActivity.Id id) {
            ProvisioningActivity activity = new ProvisioningActivity(id);
            stats.log.add(activity);
            return activity;
        }

        @Override @Restricted(DoNotUse.class)
        public void onComplete(NodeProvisioner.PlannedNode plannedNode, Node node) {
            ProvisioningActivity.Id id = getIdFor(plannedNode);
            if (id != null) {
                onComplete(id, node);
            }
        }

        /**
         * Inform plugin provisioning has started. This is only needed when provisioned outside {@link NodeProvisioner}.
         */
        public @CheckForNull ProvisioningActivity onComplete(@Nonnull ProvisioningActivity.Id id, @Nonnull Node node) {
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity != null) {
                // TODO do we want this in ID anyway?
                activity.getId().rename(node.getDisplayName());
            }
            return activity;
        }

        @Override @Restricted(DoNotUse.class)
        public void onFailure(NodeProvisioner.PlannedNode plannedNode, Throwable t) {
            ProvisioningActivity.Id id = getIdFor(plannedNode);
            if (id != null) {
                onFailure(id, t);
            }
        }

        /**
         * Inform plugin provisioning has started. This is only needed when provisioned outside {@link NodeProvisioner}.
         */
        public @CheckForNull ProvisioningActivity onFailure(@Nonnull ProvisioningActivity.Id id, @Nonnull Throwable throwable) {
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity != null) {
                activity.attach(ProvisioningActivity.Phase.PROVISIONING, new PhaseExecutionAttachment.ExceptionAttachment(
                        ProvisioningActivity.Status.FAIL, "Provisioning failed", throwable
                ));
            }
            return activity;
        }

        public static ProvisioningListener get() {
            return Jenkins.getInstance().getExtensionList(ProvisioningListener.class).get(0);
        }
    }

    @Restricted(NoExternalUse.class)
    private static class OperationListener extends ComputerListener {

        private final CloudStatistics stats;

        public OperationListener(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            System.out.println("LAUNCHING " + c.getDisplayName());
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // TODO it can be launched several times
            activity.enter(ProvisioningActivity.Phase.LAUNCHING);
        }

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // TODO attach details
        }

        @Override public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // TODO it can happen several times
            activity.enter(ProvisioningActivity.Phase.OPERATING);
        }
    }

    // TODO Replace with better extension point https://issues.jenkins-ci.org/browse/JENKINS-33780
    // TODO does not support slave rename at all. I tried to mark the node with property but ComputerListener#preLaunch might not have access to Node instance:
    //    at hudson.slaves.SlaveComputer._connect(SlaveComputer.java:219)
    //    at hudson.model.Computer.connect(Computer.java:339)
    //    at hudson.slaves.RetentionStrategy$1.start(RetentionStrategy.java:108)
    //    at hudson.model.AbstractCIBase.updateComputer(AbstractCIBase.java:129)
    //    at hudson.model.AbstractCIBase.updateComputerList(AbstractCIBase.java:180)
    //            - locked <0x13cf> (a java.lang.Object)
    //    at jenkins.model.Jenkins.updateComputerList(Jenkins.java:1200)
    //    at jenkins.model.Jenkins.setNodes(Jenkins.java:1696)
    //    at jenkins.model.Jenkins.addNode(Jenkins.java:1678)
    //            - locked <0x13a6> (a hudson.model.Hudson)
    //    at org.jvnet.hudson.test.JenkinsRule.createSlave(JenkinsRule.java:814)
    @Restricted(NoExternalUse.class)
    /*package*/ static class SlaveCompletionDetector extends PeriodicWork {

        private final CloudStatistics stats;

        public SlaveCompletionDetector(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override
        public long getRecurrencePeriod() {
            return MIN * 10;
        }

        @Override
        protected void doRun() throws Exception {
            List<ProvisioningActivity.Id> trackedExisting = new ArrayList<>();
            for (Computer computer : Jenkins.getInstance().getComputers()) {
                if (computer instanceof TrackedItem) {
                    trackedExisting.add(((TrackedItem) computer).getId());
                }
            }

            for (ProvisioningActivity activity: stats.log) {
                Map<ProvisioningActivity.Phase, ProvisioningActivity.PhaseExecution> executions = activity.getPhaseExecutions();
                if (executions.get(ProvisioningActivity.Phase.COMPLETED) != null) continue; // Completed already
                if (trackedExisting.contains(activity.getId())) continue; // Still running

                activity.enter(ProvisioningActivity.Phase.COMPLETED);
            }
        }
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(NodeProvisioner.PlannedNode plannedNode) {
        if (!(plannedNode instanceof TrackedItem)) {
            LOGGER.info("No support for cloud-stats-plugin by " + plannedNode.getClass());
            return null;
        }

        return ((TrackedItem) plannedNode).getId();
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(Computer computer) {
        if (!(computer instanceof TrackedItem)) {
            LOGGER.info("No support for cloud-stats-plugin by " + computer.getClass());
            return null;
        }

        return ((TrackedItem) computer).getId();
    }

    private @CheckForNull ProvisioningActivity getActivityFor(ProvisioningActivity.Id id) {
        for (ProvisioningActivity activity : log.toList()) {
            if (activity.isFor(id)) {
                return activity;
            }
        }

        LOGGER.log(Level.WARNING, "No activity tracked for " + id, new Exception());
        return null;
    }
}
