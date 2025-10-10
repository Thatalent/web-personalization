package com.resonate.personalize.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
public class CliRunner implements ApplicationRunner {
  private final ApplicationContext ctx;
  private final AssetIngestCommand ingest;

  public CliRunner(ApplicationContext ctx, AssetIngestCommand ingest) {
    this.ctx = ctx;
    this.ingest = ingest;
  }

  @Override public void run(ApplicationArguments args) {
    // only run if first arg is 'ingest'
    if (args.getNonOptionArgs().isEmpty() || !"ingest".equalsIgnoreCase(args.getNonOptionArgs().get(0))) {
      return; // not CLI mode; let the web app run normally
    }
    String[] tail = args.getSourceArgs().length > 1
        ? java.util.Arrays.copyOfRange(args.getSourceArgs(), 1, args.getSourceArgs().length)
        : new String[0];

    int exit = new CommandLine(ingest).execute(tail);
    // Terminate after CLI finishes (so process exits)
    System.exit(exit);
  }
}
