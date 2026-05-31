package org.gpc4j.easements.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import net.ravendb.client.documents.session.IDocumentSession;

/**
 * REST controller that ingests a scanned easement PDF. OCR is delegated to the
 * external PaddleOCR service, which returns a fully-populated {@link EasementDoc}.
 * Page images are rendered locally via PDFBox and stored as RavenDB attachments.
 */
@RestController
@RequestMapping("/api")
public class EasementController {

  private static final Logger log =
      LoggerFactory.getLogger(EasementController.class);

  private static final float RENDER_DPI = 150f;

  private final PaddleOcrService paddleOcrService;
  private final IDocumentSession session;

  /**
   * Constructs the controller with its required dependencies.
   *
   * @param paddleOcrService service that posts PDFs to the PaddleOCR endpoint
   * @param session          request-scoped RavenDB session
   */
  public EasementController(
      PaddleOcrService paddleOcrService,
      IDocumentSession session) {

    this.paddleOcrService = paddleOcrService;
    this.session = session;
  }

  /**
   * Accepts a scanned easement PDF, delegates OCR to the PaddleOCR service,
   * renders each page to a PNG for storage, and persists the result in RavenDB.
   *
   * <p>The {@link EasementDoc} returned by PaddleOCR is stored under the
   * original filename as its document key. Each rendered page is attached as
   * {@code page-1.png}, {@code page-2.png}, etc.
   *
   * @param file the uploaded PDF
   * @return the RavenDB document ID on success, or 400 if the filename is absent
   * @throws IOException if reading the PDF, rendering a page, or the OCR call fails
   */
  @PostMapping("/easement")
  public ResponseEntity<String> ingest(
      @RequestParam("file") MultipartFile file)
      throws IOException {

    String filename = file.getOriginalFilename();

    if (filename == null || filename.isBlank()) {
      return ResponseEntity.badRequest().body("File must have a filename");
    }

    log.info("Ingesting easement PDF: {}", filename);

    byte[] pdfBytes = file.getBytes();

    EasementDoc doc = paddleOcrService.process(filename, pdfBytes);

    // Render pages locally for RavenDB image attachments.
    // Index access required (pageImages.get(i)), so ArrayList is correct here.
    List<byte[]> pageImages = new ArrayList<>();
    try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {

      PDFRenderer renderer = new PDFRenderer(pdf);
      int pageCount = pdf.getNumberOfPages();
      log.info("PDF has {} page(s)", pageCount);

      for (int i = 0; i < pageCount; i++) {
        BufferedImage image =
            renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bos);
        pageImages.add(bos.toByteArray());
        log.debug("Page {}: rendered {} bytes", i + 1, bos.size());
      }
    }

    session.store(doc, filename);

    for (int i = 0; i < pageImages.size(); i++) {
      String attachmentName = "page-" + (i + 1) + ".png";
      session.advanced().attachments().store(
          doc,
          attachmentName,
          new ByteArrayInputStream(pageImages.get(i)),
          "image/png");
      log.debug("Queued attachment: {}", attachmentName);
    }

    session.saveChanges();
    log.info("Stored EasementDoc '{}' with {} attachment(s)",
        filename, pageImages.size());

    return ResponseEntity.ok(filename);
  }

}
