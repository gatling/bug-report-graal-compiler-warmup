package io.gatling.benchmark;

import io.netty.util.ResourceLeakDetector;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Main {
  private static final String hostname = "localhost";
  private static final int port = 8000;
  private static final int idleTimeoutMillis = 5000;

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.out.println(
          "Expected args: <connections> <testDurationSeconds> <resultsFilePath.csv>");
    } else {
      final var connections = Integer.parseInt(args[0]);
      final var durationSeconds = Integer.parseInt(args[1]);
      final var resultsFilePath = Paths.get(args[2]);

      ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

      final List<ResultLine> results;
      try (final var server = new Server(port, idleTimeoutMillis);
          final var client = new Client(hostname, port)) {
        results = client.run(connections, durationSeconds);
      }

      final var csvLines = formatCsv(results);
      Files.write(resultsFilePath, csvLines);
    }
  }

  private static ArrayList<String> formatCsv(List<ResultLine> results) {
    final var startNanos = results.getFirst().elapsedNanos();
    final var csvLines = new ArrayList<String>();
    csvLines.add("Elapsed time (s),Requests per second");
    for (int i = 0; i < results.size() - 1; i++) {
      final var elapsedTimeSeconds =
          (results.get(i + 1).elapsedNanos() - startNanos) / 1_000_000_000d;
      final var windowDurationSeconds =
          (results.get(i + 1).elapsedNanos() - results.get(i).elapsedNanos()) / 1_000_000_000d;
      final var rps = Math.round(results.get(i + 1).requests() / windowDurationSeconds);
      csvLines.add(String.format(Locale.US, "%.3f,%d", elapsedTimeSeconds, rps));
    }
    return csvLines;
  }
}
