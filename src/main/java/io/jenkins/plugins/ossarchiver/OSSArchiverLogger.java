package io.jenkins.plugins.ossarchiver;

import hudson.model.TaskListener;

public class OSSArchiverLogger {
    private static final String TAG = "OSSArchiver";

    private final TaskListener listener;

    public OSSArchiverLogger(TaskListener listener) {
        this.listener = listener;
    }

    public void info(String message) {
        println("INFO", message, null);
    }

    public void info(String message, Throwable throwable) {
        println("INFO", message, throwable);
    }

    public void warn(String message) {
        println("WARN", message, null);
    }

    public void warn(String message, Throwable throwable) {
        println("WARN", message, throwable);
    }

    public void error(String message) {
        println("ERROR", message, null);
    }

    public void error(String message, Throwable throwable) {
        println("ERROR", message, throwable);
    }

    private void println(String level, String message, Throwable throwable) {
        listener.getLogger().println("[" + TAG + "][" + level + "]" + message);
        if (throwable != null) {
            throwable.printStackTrace(listener.getLogger());
        }
    }
}
