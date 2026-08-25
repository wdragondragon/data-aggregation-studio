package com.jdragon.studio.worker.runtime;

import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.plugin.AbstractJobPlugin;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Cancels both framework versions that expose JobContainer.cancel() and older
 * versions that only expose their active plugins and execution threads.
 */
@Slf4j
public final class JobContainerCancellation {

    private static final Set<Object> CANCELLED = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private JobContainerCancellation() {
    }

    public static void cancel(Object container) {
        if (container == null) {
            return;
        }
        synchronized (CANCELLED) {
            if (!CANCELLED.add(container)) {
                return;
            }
        }
        if (invokeFrameworkCancel(container)) {
            return;
        }
        markCommunicationFailed(container);
        destroyPlugin(readField(container, "readerJobPlugin"));
        destroyPlugin(readField(container, "writerJobPlugin"));
        interrupt(readField(container, "readerThread"));
        interrupt(readField(container, "writerThread"));
        interrupt(readField(container, "reportThread"));
    }

    private static boolean invokeFrameworkCancel(Object container) {
        try {
            Method cancel = container.getClass().getMethod("cancel");
            cancel.invoke(container);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            log.debug("Framework JobContainer.cancel() failed; applying compatibility cancellation", failure);
            return false;
        }
    }

    private static void markCommunicationFailed(Object container) {
        try {
            Method reporterAccessor = container.getClass().getMethod("getJobPointReporter");
            Object reporter = reporterAccessor.invoke(container);
            if (reporter == null) {
                return;
            }
            Method communicationAccessor = reporter.getClass().getMethod("getTrackCommunication");
            Object value = communicationAccessor.invoke(reporter);
            if (value instanceof Communication) {
                Communication communication = (Communication) value;
                communication.setState(State.FAILED);
                if (communication.getThrowable() == null) {
                    communication.setThrowable(new InterruptedException("JobContainer cancelled"));
                }
            }
        } catch (ReflectiveOperationException failure) {
            log.debug("Unable to update JobContainer communication during compatibility cancellation", failure);
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                log.debug("Unable to access JobContainer field {}", name, failure);
                return null;
            }
        }
        return null;
    }

    private static void destroyPlugin(Object value) {
        if (!(value instanceof AbstractJobPlugin)) {
            return;
        }
        try {
            ((AbstractJobPlugin) value).destroy();
        } catch (Throwable failure) {
            log.debug("Plugin destroy failed during JobContainer compatibility cancellation", failure);
        }
    }

    private static void interrupt(Object value) {
        if (value instanceof Thread && ((Thread) value).isAlive()) {
            ((Thread) value).interrupt();
        }
    }
}
