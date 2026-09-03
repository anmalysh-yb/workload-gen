package com.amalyshev.workload;

import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

@SpringBootApplication
public class WorkloadGenApplication {

  public static void main(String[] args) {
    // Argument parsing happens before the Spring context starts, because the connection
    // options have to become spring.datasource.* properties in time for the DataSource.
    CommandLine commandLine = new CommandLine(new WorkloadCli())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setUnmatchedArgumentsAllowed(true)
        .setUnmatchedOptionsArePositionalParams(false);
    commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
      // Bad input is the user's problem to fix, so report it like a usage error. Anything else
      // is a real failure and keeps its stack trace.
      if (exception instanceof IllegalArgumentException
          || exception instanceof UncheckedIOException) {
        command.getErr().println(command.getColorScheme().errorText(exception.getMessage()));
        command.getErr().println("Try '" + command.getCommandName() + " --help'.");
        return CommandLine.ExitCode.USAGE;
      }
      throw exception;
    });
    int exitCode = commandLine.execute(args);
    // Workload threads that are parked in an uninterruptible JDBC read outlive shutdownNow, so
    // exit explicitly rather than waiting for the JVM to run out of non-daemon threads.
    System.exit(exitCode);
  }
}
