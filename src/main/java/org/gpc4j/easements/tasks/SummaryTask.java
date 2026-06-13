package org.gpc4j.easements.tasks;

import java.util.List;

import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Hourly reporting task that logs transcription progress across all
 * {@link EasementDoc} documents: how many pages have been processed, how many
 * remain, and the percentage complete.
 *
 * <p>Active only under the {@code k8s} or {@code test} Spring profile.
 */
@Profile({"k8s", "test"})
@Component
public class SummaryTask {

  private static final Logger log = LoggerFactory.getLogger(SummaryTask.class);

  private final IDocumentStore store;

  /**
   * Constructs the task with the RavenDB document store.
   *
   * @param store the singleton RavenDB document store
   */
  public SummaryTask(IDocumentStore store) {

    this.store = store;
  }


  /**
   * Queries all {@link EasementDoc} records, tallies transcribed and
   * untranscribed {@link EasementPage} entries, and logs a one-line progress
   * summary at INFO level.
   *
   * <p>Runs once per hour. An initial delay of 30 seconds allows the
   * application to finish startup before the first run.
   */
  @Scheduled(initialDelay = 30_000, fixedRate = 3_600_000)
  public void summarize() {

    try (IDocumentSession session = store.openSession()) {

      int totalPages = session
        .query(EasementDoc.class)
        .selectFields(Integer.class, "pageCount")
        .toList()
        .stream()
        .mapToInt(i -> i == null ? 0 : i)
        .sum();

      List<EasementDoc> docs = session.query(EasementDoc.class).toList();

      long processedPages = docs
        .stream()
        .filter(d -> d.getPages() != null)
        .flatMap(d -> d.getPages().stream())
        .filter(p -> p.getAiServiceName() != null && !p.getAiServiceName().isBlank())
        .count();

      long remainingPages = totalPages - processedPages;

      String pct = totalPages > 0
        ? String.format("%.1f", processedPages * 100.0f / totalPages) : "0.0";

      log
        .info(
          "Transcription progress: {}/{} pages complete ({}%), {} remaining"
            + " across {} documents",
          processedPages, totalPages, pct, remainingPages, docs.size());

    } catch (Exception e) {
      log.error("SummaryTask failed", e);
    }
  }

}
