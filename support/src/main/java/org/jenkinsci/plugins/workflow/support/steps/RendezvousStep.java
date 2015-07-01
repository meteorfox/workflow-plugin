package org.jenkinsci.plugins.workflow.support.steps;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link Step} that pauses until a specified number of flow builds reach
 * the rendezvous point.
 *
 * @author Carlos L. Torres
 */
public class RendezvousStep extends AbstractStepImpl {

    public final String name;
    public final Integer minimum;

    @DataBoundConstructor public RendezvousStep(String name, Integer minimum) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("must specify name");
        }
        this.name = name;

        if (minimum == null || minimum < 1) {
            throw new IllegalArgumentException("must specify minimum >= 1");
        }
        this.minimum = minimum;
    }

    @Extension public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(RendezvousStepExecution.class);
        }

        @Override
        public String getDisplayName() {
            return "Rendezvous";
        }

        @Override
        public String getFunctionName() {
            return "rendezvous";
        }
    }
}
