package io.github.hello09x.fakeplayer.core.util.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TasksTest {

    @Test
    void foliaTaskCancelsAHiddenImplementationThroughThePublicApi() throws Exception {
        var scheduled = new HiddenScheduledTask();
        var implementation = Class.forName(Tasks.class.getName() + "$FoliaTask");
        var constructor = implementation.getDeclaredConstructor(Object.class);
        constructor.setAccessible(true);
        var task = (Tasks.Task) constructor.newInstance(scheduled);

        task.cancel();
        task.cancel();

        assertTrue(scheduled.cancelled);
        assertEquals(1, scheduled.cancelCalls);
    }

    private static final class HiddenScheduledTask implements ScheduledTask {
        private boolean cancelled;
        private int cancelCalls;

        @Override
        public Plugin getOwningPlugin() {
            return null;
        }

        @Override
        public boolean isRepeatingTask() {
            return true;
        }

        @Override
        public CancelledState cancel() {
            cancelCalls++;
            cancelled = true;
            return null;
        }

        @Override
        public ExecutionState getExecutionState() {
            return null;
        }
    }
}
