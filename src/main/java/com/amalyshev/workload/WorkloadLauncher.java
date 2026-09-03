package com.amalyshev.workload;

import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;

/**
 * Wires the parsed CLI into a Spring context and runs the resolved chain.
 *
 * <p>The chain runs here rather than in a {@code CommandLineRunner} so that the exit code reflects
 * the outcome and so that a test context never starts a workload.
 */
final class WorkloadLauncher {

  private WorkloadLauncher() {
  }

  static int launch(WorkloadCli cli) {
    if (cli.selection.listWorkloads) {
      return listWorkloads();
    }
    List<WorkloadStep> steps = cli.steps();
    // Check the names against a classpath scan first. Resolving them from the context would
    // require a reachable cluster, so a typo would be reported as a connection failure.
    WorkloadRegistry.requireKnown(steps);
    Map<String, Object> properties = cli.springProperties(steps);
    if (cli.selection.dryRun) {
      return dryRun(cli, steps, properties);
    }
    try (ConfigurableApplicationContext context = start(cli, properties)) {
      return context.getBean(WorkloadChainRunner.class)
          .run(steps, cli.selection.continueOnError);
    }
  }

  private static ConfigurableApplicationContext start(WorkloadCli cli,
      Map<String, Object> properties) {
    SpringApplication application = new SpringApplication(WorkloadGenApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.addInitializers(context -> {
      MutablePropertySources sources = context.getEnvironment().getPropertySources();
      MapPropertySource cliSource = new MapPropertySource("workload-cli", properties);
      // Above application.properties, but still below any explicit --spring.* argument.
      String commandLine = SimpleCommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
      if (sources.contains(commandLine)) {
        sources.addAfter(commandLine, cliSource);
      } else {
        sources.addFirst(cliSource);
      }
    });
    if (!cli.passthrough.isEmpty()) {
      // These are not CLI options; Spring Boot turns "--a.b=c" into a property and ignores the
      // rest. Say so, so a mistyped flag is visible instead of silently doing nothing.
      System.out.println("Forwarding unrecognised argument(s) to Spring Boot: "
          + String.join(" ", cli.passthrough));
    }
    return application.run(cli.passthrough.toArray(new String[0]));
  }

  /** Lists workloads by classpath scan, so it works without a reachable cluster. */
  private static int listWorkloads() {
    System.out.println("Available workloads:");
    WorkloadRegistry.discoverNames().forEach(name -> System.out.println("  " + name));
    return 0;
  }

  private static int dryRun(WorkloadCli cli, List<WorkloadStep> steps,
      Map<String, Object> properties) {
    System.out.println("Connection:");
    properties.forEach((key, value) -> System.out.println("  " + key + " = "
        + (key.endsWith("password") ? mask(String.valueOf(value)) : value)));
    System.out.println("Chain (" + steps.size() + " step(s), "
        + Durations.format(WorkloadCli.totalDuration(steps)) + " total):");
    for (int i = 0; i < steps.size(); i++) {
      System.out.println("  " + (i + 1) + ". " + steps.get(i));
    }
    return 0;
  }

  private static String mask(String password) {
    return password.isEmpty() ? "<empty>" : "*".repeat(Math.min(password.length(), 8));
  }
}
