package org.gpc4j.easements.services;

import static org.gpc4j.easements.controller.EasementController.CONFIDENCE_PATTERN;
import static org.gpc4j.easements.controller.EasementController.OCR_PROMPT;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

import org.gpc4j.easements.model.AIPrompt;
import org.gpc4j.easements.model.AIResponse;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.AttachmentName;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Background task that finds one {@link EasementDoc} whose {@code aiServiceName}
 * field is unset and reprocesses its page-image attachments specifically through
 * {@link GeminiService}.
 *
 * <p>Runs every 5 minutes from 1:00 AM to 8:55 AM Pacific time (96 invocations
 * per day). Only one document is processed per run to avoid rate-limit pressure.
 * On a Gemini 429 response the task pauses for 10 minutes before trying again.
 *
 * <p>Active only under the {@code k8s} or {@code test} Spring profile.
 */
@Profile({"k8s", "test"})
@Component
public class GeminiReprocessingTask {

  private static final Logger log = LoggerFactory
    .getLogger(GeminiReprocessingTask.class);

  private static final long QUOTA_PAUSE_MS = 10 * 60 * 1000L;

  private final IDocumentStore store;
  private final GeminiService geminiService;

  /** Epoch-ms timestamp before which reprocessing is suppressed after a 429. */
  private volatile long pauseUntil = 0L;

  /**
   * Constructs the task with the RavenDB document store and the Gemini AI
   * service.
   *
   * @param store          the singleton RavenDB document store; sessions are
   *                       opened per-run because this task runs outside an HTTP
   *                       request scope
   * @param geminiService  the Gemini vision service used to extract text from
   *                       page images
   */
  public GeminiReprocessingTask(IDocumentStore store, GeminiService geminiService) {

    this.store = store;
    this.geminiService = geminiService;
  }


  /**
   * Selects one {@link EasementDoc} whose {@code aiServiceName} is unset,
   * delegates processing to {@link #processDoc}, and persists the result.
   *
   * <p>Runs every 5 minutes from 1:00 AM to 8:55 AM Pacific time.
   */
  @Scheduled(cron = "0 0/5 1-8 * * *", zone = "America/Los_Angeles")
  public void reprocessOne() {

    long now = System.currentTimeMillis();
    if (now < pauseUntil) {
      log
        .debug(
          "Gemini reprocessing task paused for {} more seconds due to quota limit",
          (pauseUntil - now) / 1000);
      return;
    }

    log
      .debug(
        "Gemini reprocessing task: searching for EasementDoc with no aiServiceName");

    try (IDocumentSession session = store.openSession()) {

      EasementDoc doc = session
        .query(EasementDoc.class)
        .openSubclause()
        .negateNext()
        .whereExists("aiServiceName")
        .orElse()
        .whereEquals("aiServiceName", (Object) null)
        .orElse()
        .whereEquals("aiServiceName", "")
        .closeSubclause()
        .firstOrDefault();

      if (doc == null) {
        log.info("Gemini reprocessing task: all documents are up to date");
        return;
      }

      log.debug("Gemini reprocessing '{}'", doc.getId());
      processDoc(session, doc);
      session.saveChanges();
      log
        .info("Gemini reprocessed '{}': {} page(s) via {}/{}", doc.getId(),
          doc.getPageCount(), doc.getAiServiceName(), doc.getAiModel());

    } catch (QuotaExceededException e) {
      pauseUntil = System.currentTimeMillis() + QUOTA_PAUSE_MS;
      log.warn("Gemini quota exceeded; pausing Gemini reprocessing for 10 minutes");
    } catch (Exception e) {
      log.error("Gemini reprocessing task failed unexpectedly", e);
    }
  }


  /**
   * Fetches each page-image attachment for {@code doc} from RavenDB, submits
   * it to {@link GeminiService} for text extraction, and updates {@code doc} in
   * place with the resulting {@link EasementPage} list and AI provenance fields.
   *
   * <p>Individual page failures are logged and re-thrown. Throws
   * {@link IOException} if no pages could be extracted at all, leaving
   * {@code doc} unmodified so the caller can skip the save.
   *
   * @param session open RavenDB session used to fetch page-image attachments
   * @param doc     the document to reprocess; mutated in place on success
   * @throws IOException if no pages were successfully extracted
   */
  void processDoc(IDocumentSession session, EasementDoc doc) throws IOException {

    AttachmentName[] names = session.advanced().attachments().getNames(doc);
    int totalPages = (int) Arrays
      .stream(names)
      .filter(a -> a.getName().matches("page-\\d+\\.png"))
      .count();

    List<EasementPage> pages = new LinkedList<>();
    for (int i = 1; i <= totalPages; i++) {
      String attachmentName = "page-" + i + ".png";
      log
        .debug("Extracting text from attachment '{}' ({}/{})", attachmentName, i,
          totalPages);

      try (CloseableAttachmentResult att = session
        .advanced()
        .attachments()
        .get(doc.getId(), attachmentName)) {

        if (att == null) {
          log
            .warn("Attachment '{}' not found for '{}'; skipping page",
              attachmentName, doc.getId());
          continue;
        }

        byte[] imageBytes = att.getData().readAllBytes();

        AIPrompt prompt = AIPrompt
          .builder()
          .text(OCR_PROMPT)
          .image(imageBytes)
          .build();

        AIResponse aiResponse = geminiService.queryResponse(prompt);
        log
          .debug("Page {}: Gemini returned {} chars", i, aiResponse.text().length());

        float confidence = 0f;
        List<String> lines = new LinkedList<>();
        for (String raw : aiResponse.text().split("\n")) {
          String trimmed = raw.trim();
          if (trimmed.isBlank()) {
            continue;
          }
          Matcher cm = CONFIDENCE_PATTERN.matcher(trimmed);
          if (cm.find()) {
            confidence = Float.parseFloat(cm.group(1));
          } else {
            lines.add(trimmed);
          }
        }

        log.debug("Page {}: {} lines, confidence {}%", i, lines.size(), confidence);
        pages
          .add(new EasementPage(i, lines, confidence, aiResponse.aiServiceName(),
            aiResponse.aiModel()));

      } catch (IOException e) {
        log
          .error("Failed to process page {} of '{}': {}", i, doc.getId(),
            e.getMessage());
        throw e;
      }
    }

    if (pages.isEmpty()) {
      throw new IOException("No pages could be extracted for '" + doc.getId() + "'");
    }

    doc.setPages(pages);
    doc.setPageCount(pages.size());
    doc.setAiServiceName(geminiService.getClass().getSimpleName());
    doc.setAiModel(geminiService.getModel());
  }

}
