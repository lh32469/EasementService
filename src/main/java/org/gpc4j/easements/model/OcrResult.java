package org.gpc4j.easements.model;

import java.util.List;

/**
 * Value object returned by {@code TesseractService.extractText()}, bundling
 * the OCR text lines and the mean word-level confidence score (0–100) for
 * a single image so both can be captured in one OCR pass.
 *
 * @param lines      non-blank text lines extracted from the image
 * @param confidence mean Tesseract word confidence across the image (0–100)
 */
public record OcrResult(List<String> lines, float confidence) {

}
