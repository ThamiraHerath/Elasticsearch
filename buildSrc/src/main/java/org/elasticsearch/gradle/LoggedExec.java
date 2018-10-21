package org.elasticsearch.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;
import org.gradle.process.BaseExecSpec;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;
import org.gradle.process.JavaExecSpec;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.function.Function;

/**
 * A wrapper around gradle's Exec task to capture output and log on error.
 */
@SuppressWarnings("unchecked")
public class LoggedExec extends Exec {

    public LoggedExec() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (getLogger().isInfoEnabled() == false) {
            setStandardOutput(output);
            setErrorOutput(output);
            setIgnoreExitValue(true);
            doLast((unused) -> {
                    if (getExecResult().getExitValue() != 0) {
                        try {
                            for (String line : output.toString("UTF-8").split("\\R")) {
                                getLogger().error(line);
                            }
                        } catch (UnsupportedEncodingException e) {
                            throw new GradleException("Failed to read exec output", e);
                        }
                        throw new GradleException(
                            String.format(
                                "Process '%s %s' finished with non-zero exit value %d",
                                getExecutable(),
                                getArgs(),
                                getExecResult().getExitValue()
                            )
                        );
                    }
                }
            );
        }
    }

    public static ExecResult exec(Project project, Action<ExecSpec> action) {
        return genericExec(project, project::exec, action);
    }

    public static ExecResult javaexec(Project project, Action<JavaExecSpec> action) {
        return genericExec(project, project::javaexec, action);
    }

    private static <T extends BaseExecSpec>  ExecResult genericExec(
        Project project,
        Function<Action<T>,ExecResult> function,
        Action<T> action
    ) {
        if (project.getLogger().isInfoEnabled()) {
            return function.apply(action);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            return function.apply(spec -> {
                spec.setStandardOutput(output);
                spec.setErrorOutput(output);
                action.execute(spec);
            });
        } catch (Exception e) {
            try {
                for (String line : output.toString("UTF-8").split("\\R")) {
                    project.getLogger().error(line);
                }
            } catch (UnsupportedEncodingException ue) {
                throw new GradleException("Failed to read exec output", ue);
            }
            throw e;
        }
    }
}
