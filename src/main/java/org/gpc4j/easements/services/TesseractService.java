package org.gpc4j.easements.services;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/**
 * Spring service that performs OCR on image bytes using the local Tesseract
 * engine via Tess4J. Replaces the former AWS Textract integration.
 *
 * <p>A new {@link Tesseract} instance is created per call because the
 * underlying JNA binding is not thread-safe.
 *
 * <p>Tesseract must be installed on the host (or container image). The
 * {@code tesseract.datapath} property must point to the directory that
 * contains the language data files (e.g. {@code eng.traineddata}).
 */
@Service
public class TesseractService {

  private static final Logger log =
      LoggerFactory.getLogger(TesseractService.class);

  private final String datapath;

  /**
   * Constructs the service with the configured Tesseract data directory.
   *
   * @param datapath path to the tessdata directory
   *                 (e.g. {@code /usr/share/tesseract-ocr/5/tessdata})
   */
  public TesseractService(
      @Value("${tesseract.datapath}") String datapath) {

    this.datapath = datapath;
    log.info("TesseractService initialised — datapath={}", datapath);
  }

  /**
   * Runs OCR on the supplied PNG or JPEG image bytes and returns the detected
   * lines of text, with blank lines removed.
   *
   * @param imageBytes raw image bytes
   * @return ordered list of non-blank text lines; never null
   * @throws IOException if the image cannot be decoded or Tesseract fails
   */
  public List<String> extractLines(byte[] imageBytes) throws IOException {

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));

    ITesseract tesseract = new Tesseract();
    tesseract.setDatapath(datapath);
    tesseract.setLanguage("eng");

    String raw;
    try {
      raw = tesseract.doOCR(image);
    } catch (TesseractException e) {
      throw new IOException("Tesseract OCR failed: " + e.getMessage(), e);
    }

    List<String> lines = new LinkedList<>();
    for (String line : raw.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.isBlank()) {
        lines.add(trimmed);
      }
    }

    log.debug("Extracted {} lines from image", lines.size());
    return lines;
  }

}
