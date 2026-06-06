package org.gpc4j.easements.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.services.PaddleOcrService;
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
 * <p>PDF ingestion is async: the PDF is forwarded to the PaddleOCR service
 * (which responds {@code 201 Accepted}), page images are rendered locally and
 * stored as RavenDB attachments on a placeholder document, and a
 * {@code 202 Accepted} is returned. PaddleOCR delivers the completed
 * {@link EasementDoc} via the {@code /api/easement/import} callback.
 */
@RestController
@RequestMapping("/api")
public class EasementController {

  private static final Logger log = LoggerFactory
    .getLogger(EasementController.class);

  private static final float RENDER_DPI = 150f;

  private final PaddleOcrService paddleOcrService;
  private final IDocumentSession session;

  /**
   * Constructs the controller with its required dependencies.
   *
   * @param paddleOcrService service that posts PDFs to the PaddleOCR endpoint
   * @param session          request-scoped RavenDB session
   */
  public EasementController(PaddleOcrService paddleOcrService,
    IDocumentSession session) {

    this.paddleOcrService = paddleOcrService;
    this.session = session;
  }


  /**
   * Accepts a scanned easement PDF, forwards it to the PaddleOCR service, and
   * stores a placeholder {@link EasementDoc} in RavenDB with the rendered page
   * images as attachments. Returns {@code 202 Accepted} immediately; the
   * PaddleOCR service populates the full document data via the import callback.
   *
   * <p>RavenDB preserves attachments when a document is later overwritten by
   * the callback, so the page images remain accessible once OCR completes.
   *
   * @param file the uploaded PDF
   * @return {@code 202 Accepted} with the document ID, or {@code 400} if the
   *         filename is absent
   * @throws IOException if reading or rendering the PDF fails
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

    // Submit to PaddleOCR; throws RestClientException on non-2xx.
    paddleOcrService.process(filename, pdfBytes);

    // Render pages locally so attachments can be stored immediately.
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

    // Store a placeholder document so attachments have a document to live on.
    // The PaddleOCR callback will overwrite this with full OCR data; RavenDB
    // preserves attachments across document updates.
    EasementDoc placeholder = new EasementDoc();
    placeholder.setId(filename);
    placeholder.setFilename(filename);
    placeholder.setPageCount(pageImages.size());
    placeholder.setCreatedAt(Instant.now());

    session.store(placeholder, filename);

    for (int i = 0; i < pageImages.size(); i++) {
      String attachmentName = "page-" + (i + 1) + ".png";
      session.advanced().attachments().store(placeholder, attachmentName,
        new ByteArrayInputStream(pageImages.get(i)), "image/png");
      log.debug("Queued attachment: {}", attachmentName);
    }

    session.saveChanges();
    log.info(
      "Placeholder stored for '{}' with {} attachment(s); awaiting OCR callback",
      filename, pageImages.size());

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
