package org.jenkinsci.plugins.workflow.support.steps;

import com.google.inject.Inject;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.InvisibleAction;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;

import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.logging.Level.WARNING;


public class RendezvousStepExecution extends AbstractStepExecutionImpl {
    private static final Logger LOGGER = Logger.getLogger(RendezvousStepExecution.class.getName());

    @StepContextParameter
    /*package*/ transient Run run;

    @StepContextParameter private transient TaskListener listener;
    @StepContextParameter private transient FlowNode node;

    @Inject(optional=true) private transient RendezvousStep step;

    private final class RendezvousAction extends InvisibleAction {
        private final String rendezvousPointName;

        RendezvousAction(String rendezvousPointName) {
            this.rendezvousPointName = rendezvousPointName;
        }

        public String getRendezvousPointName() {
            return this.rendezvousPointName;
        }
    }

    @Override
    public boolean start() throws Exception {
        node.addAction(new LabelAction(step.name));
        node.addAction(new RendezvousAction(step.name));
        enter(run, node, getContext(), step.name, step.minimum);
        return false;
    }

    @Override
    public void stop(@Nonnull Throwable cause) throws Exception {
        load();
        cleanUp();
        save();
    }

    private static XmlFile getConfigFile() throws IOException {
        Jenkins j = Jenkins.getInstance();
        if (j == null) {
            throw new IOException("Jenkins is not running"); // do not use Jenkins.getActiveInstance() as that is an ISE
        }
        return new XmlFile(new File(j.getRootDir(), RendezvousStep.class.getName() + ".xml"));
    }

    private static Map<String,Rendezvous> rendezvousPointsByName;

    @SuppressWarnings("unchecked")
    private static synchronized void load() {
        if (rendezvousPointsByName == null) {
            rendezvousPointsByName = new TreeMap<String,Rendezvous>();
            try {
                XmlFile configFile = getConfigFile();
                if (configFile.exists()) {
                    rendezvousPointsByName = (Map<String,Rendezvous>) configFile.read();
                }
            } catch (IOException ex) {
                LOGGER.log(WARNING, null, ex);
            }
            LOGGER.log(Level.FINE, "rendezvous load: {0}", rendezvousPointsByName);
        }
    }

    private static synchronized void save() {
        try {
            getConfigFile().write(rendezvousPointsByName);
        } catch (IOException ex) {
            LOGGER.log(WARNING, null, ex);
        }
        LOGGER.log(Level.FINE, "rendezvous save: {0}", rendezvousPointsByName);
    }

    private static synchronized void enter(Run<?,?> r, FlowNode n, StepContext context, String name, Integer minimum) {
        LOGGER.log(Level.FINE, "rendezvous enter {0} {1}", new Object[]{r, name});
        println(context, "Reached rendezvous point " + name);
        load();

        Rendezvous rendezvousPoint = rendezvousPointsByName.get(name);
        if (rendezvousPoint == null) {
            rendezvousPoint = new Rendezvous();
            rendezvousPointsByName.put(name, rendezvousPoint);
        }
        rendezvousPoint.minimum = minimum;

        rendezvousPoint.block(n.getId(), context);
        if (rendezvousPoint.holding.size() >= rendezvousPoint.minimum) {
            println(context, "Critical mass reached. Proceeding.");
            rendezvousPoint.unblock();
        } else {
            println(context, "Waiting on rendezvous " + rendezvousPoint.holding.size()
                    + " of " + rendezvousPoint.minimum);
        }

        cleanUp();
        save();
    }

    private static void cleanUp() {
        assert rendezvousPointsByName != null;
        Iterator<Map.Entry<String,Rendezvous>> it = rendezvousPointsByName.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String,Rendezvous> entry = it.next();
            Map<String,StepContext> holding = entry.getValue().holding;
            Iterator<Map.Entry<String,StepContext>> it2 = holding.entrySet().iterator();
            while (it2.hasNext()) {
                Map.Entry<String,StepContext> entryContext = it2.next();
                if (entryContext.getValue().isReady()) {
                    it2.remove();
                }
            }
            if (holding.isEmpty()) {
                it.remove();
            }
        }
    }
    private static void println(StepContext context, String message) {
        try {
            context.get(TaskListener.class).getLogger().println(message);
        } catch (Exception x) {
            LOGGER.log(WARNING, null, x);
        }
    }

    private static final class Rendezvous {
        /** Numbers of builds waiting in this rendezvous point. */
        final Map<String,StepContext> holding = new TreeMap<String, StepContext>();

        /** Minimum number of {@link #holding} required to proceed. */
        @CheckForNull Integer minimum;


        @Override public String toString() {
            return "Rendezvous[holding=" + holding + ",minimum=" + minimum + "]";
        }

        /**
         * Unblocks the rendezvous point
         */
        void unblock() {
            assert Thread.holdsLock(RendezvousStepExecution.class);
            for (Map.Entry<String,StepContext> waitingContextEntry: holding.entrySet()) {
                waitingContextEntry.getValue().onSuccess(null);
            }
        }

        /**
         * Blocks the build, and waits for other builds to reach rendezvous.
         */
        void block(String flowNodeId, StepContext waitingContext) {
            assert Thread.holdsLock(RendezvousStepExecution.class);
            holding.put(flowNodeId, waitingContext);
        }
    }

}
