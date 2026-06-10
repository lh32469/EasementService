package org.gpc4j.easements.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.gpc4j.easements.model.AIPrompt;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.gpc4j.easements.services.AIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import net.ravendb.client.documents.session.IDocumentSession;

/**
 * REST controller that ingests scanned easement PDFs and accepts direct
 * {@link EasementDoc} imports.
 *
 * <p>PDF ingestion is synchronous: each PDF page is rendered locally to a PNG
 * image, submitted to {@link AIService} for text extraction, and the resulting
 * text lines are stored in a fully-populated {@link EasementDoc} in RavenDB.
 * Page images are stored as RavenDB attachments on the same document.
 */
@RestController
@RequestMapping("/api")
public class EasementController {

  private static final Logger log = LoggerFactory
    .getLogger(EasementController.class);

  private static final float RENDER_DPI = 150f;

  public static final String OCR_PROMPT = "Read all text from this easement "
    + "document page image. "
    + "Return the text content, one line per line, exactly as it appears. "
    + "After the extracted text, add a line: CONFIDENCE: NN% where NN reflects "
    + "how clearly the text was readable in percentage.";

  /** Matches the AI-appended confidence line, e.g. {@code CONFIDENCE: 87%}. */
  private static final Pattern CONFIDENCE_PATTERN = Pattern
    .compile("CONFIDENCE:\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);

  private final AIService aiService;
  private final IDocumentSession session;

  /**
   * Constructs the controller with its required dependencies.
   *
   * @param aiService service that extracts text from page images via AI vision
   * @param session   request-scoped RavenDB session
   */
  public EasementController(AIService aiService, IDocumentSession session) {

    this.aiService = aiService;
    this.session = session;
  }


  /**
   * Accepts a scanned easement PDF, renders each page to a PNG image, submits
   * each image to {@link AIService} for text extraction, and stores the
   * resulting {@link EasementDoc} in RavenDB with the page images as
   * attachments.
   *
   * <p>Processing is synchronous; the response is returned only after all pages
   * have been processed and the document has been persisted.
   *
   * @param file the uploaded PDF
   * @return {@code 202 Accepted} with the document ID, or {@code 400} if the
   *         filename is absent
   * @throws IOException if reading, rendering, or AI text extraction fails
   */
  @PostMapping("/easement")
  public ResponseEntity<String> ingest(@RequestParam("file") MultipartFile file)
    throws IOException {

    String filename = file.getOriginalFilename();

    if (filename == null || filename.isBlank()) {
      return ResponseEntity.badRequest().body("File must have a filename");
    }

    log.info("Ingesting easement PDF: {}", filename);

    byte[] pdfBytes = file.getBytes();

    // Index access required (pageImages.get(i)), so ArrayList is correct here.
    List<byte[]> pageImages = new ArrayList<>();
    try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {

      PDFRenderer renderer = new PDFRenderer(pdf);
      int pageCount = pdf.getNumberOfPages();
      log.info("PDF has {} page(s)", pageCount);

      for (int i = 0; i < pageCount; i++) {
        BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI,
          ImageType.RGB);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bos);
        pageImages.add(bos.toByteArray());
        log.debug("Page {}: rendered {} bytes", i + 1, bos.size());
      }
    }

    List<EasementPage> pages = new LinkedList<>();
    for (int i = 0; i < pageImages.size(); i++) {
      int pageNumber = i + 1;
      log.info("Extracting text from page {}/{}", pageNumber, pageImages.size());

      AIPrompt prompt = AIPrompt.builder().text(OCR_PROMPT).image(pageImages.get(i))
        .build();

      String aiText = aiService.query(prompt);
      log.debug("Page {}: AI returned {} chars", pageNumber, aiText.length());

      // Split response into lines; detect and strip the CONFIDENCE trailer.
      float confidence = 0f;
      List<String> lines = new LinkedList<>();
      for (String raw : aiText.split("\n")) {
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

      log.debug("Page {}: {} lines, confidence {}%", pageNumber, lines.size(),
        confidence);
      pages.add(new EasementPage(pageNumber, lines, confidence));
    }

    EasementDoc doc = new EasementDoc();
    doc.setId(filename);
    doc.setFilename(filename);
    doc.setPages(pages);
    doc.setPageCount(pages.size());
    doc.setCreatedAt(Instant.now());
    doc.setAiServiceName(aiService.getClass().getSimpleName());
    doc.setAiModel(aiService.getModel());

    session.store(doc, filename);

    for (int i = 0; i < pageImages.size(); i++) {
      String attachmentName = "page-" + (i + 1) + ".png";
      session.advanced().attachments().store(doc, attachmentName,
        new ByteArrayInputStream(pageImages.get(i)), "image/png");
      log.debug("Queued attachment: {}", attachmentName);
    }

    session.saveChanges();
    log.info("Stored '{}' with {} page(s) and {} attachment(s)", filename,
      pages.size(), pageImages.size());

    return ResponseEntity.accepted().body(filename + "\n");
  }


  /**
   * Stores a pre-built {@link EasementDoc} JSON object directly in RavenDB,
   * using {@link EasementDoc#getId()} as the document key. No PDF rendering
   * or OCR is performed; the caller supplies all fields.
   *
   * <p>If the document already exists under the same key it will be
   * overwritten.
   *
   * @param doc the easement document to persist; must have a non-blank {@code id}
   * @return the stored document ID on success, or 400 if {@code id} is absent
   */
  @PostMapping("/easement/import")
  public ResponseEntity<String> importDoc(@RequestBody EasementDoc doc) {

    String id = doc.getId();

    if (id == null || id.isBlank()) {
      return ResponseEntity.badRequest().body("EasementDoc must have an id");
    }

    log.info("Importing EasementDoc '{}'", id);

    session.store(doc, id);
    session.saveChanges();

    log.info("Stored EasementDoc '{}'", id);
    return ResponseEntity.ok(id);
  }

}
