package org.gpc4j.easements.services;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;

import org.gpc4j.easements.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;

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
   * Runs OCR on the supplied PNG or JPEG image bytes and returns an
   * {@link OcrResult} containing the detected text lines and the mean
   * word-level confidence score.
   *
   * <p>Text is extracted via {@code doOCR()} to preserve line structure.
   * Confidence is derived from a second {@code getWords()} call at the
   * {@code RIL_WORD} level; if confidence cannot be obtained the score
   * defaults to 0.
   *
   * @param imageBytes raw image bytes
   * @return OCR result with non-blank text lines and mean confidence (0–100)
   * @throws IOException if the image cannot be decoded or Tesseract fails
   */
  public OcrResult extractText(byte[] imageBytes) throws IOException {

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

    float confidence = computeConfidence(tesseract, image);
    log.debug("Extracted {} lines, confidence={:.1f}", lines.size(), confidence);
    return new OcrResult(lines, confidence);
  }

  /**
   * Derives the mean word-level confidence for an image by calling
   * {@code getWords()} at {@code RIL_WORD} granularity. Returns 0 if no
   * words are detected or if the call fails.
   *
   * @param tesseract initialised {@link ITesseract} instance
   * @param image     image to evaluate
   * @return mean confidence in the range 0–100
   */
  private float computeConfidence(ITesseract tesseract, BufferedImage image) {

    try {
      List<Word> words =
          tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);

      if (words.isEmpty()) {
        return 0f;
      }

      float total = 0f;
      for (Word word : words) {
        total += word.getConfidence();
      }
      return total / words.size();

    } catch (Exception e) {
      log.warn("Could not compute OCR confidence: {}", e.getMessage());
      return 0f;
    }
  }

}
