package org.gpc4j.easements.controller;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.PageCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.documents.session.QueryStatistics;
import net.ravendb.client.primitives.Reference;

/**
 * Controller that serves the Thymeleaf search UI and streams RavenDB document
 * attachments (page images) to the browser.
 */
@Controller
public class SearchController {

  private static final Logger log =
      LoggerFactory.getLogger(SearchController.class);

  private static final int PAGE_SIZE = 15;

  /**
   * Matches the boolean operators {@code AND} and {@code OR} (uppercase,
   * surrounded by whitespace) that separate search terms in the user's query.
   * RavenDB/Corax requires each term to be in its own {@code search()} clause,
   * so we split on these operators and reconstruct the RQL ourselves.
   */
  private static final Pattern BOOL_OP =
      Pattern.compile("\\s+(AND|OR)\\s+");

  private final IDocumentSession session;

  /**
   * Constructs the controller with a request-scoped RavenDB session.
   *
   * @param session the RavenDB document session for this request
   */
  public SearchController(IDocumentSession session) {

    this.session = session;
  }

  /**
   * Redirects the application root to the search page.
   *
   * @return redirect to {@code /search}
   */
  @GetMapping("/")
  public String root() {

    return "redirect:/search";
  }

  /**
   * Renders the search page. When {@code q} is present it translates the
   * user's query into RQL and queries the {@code EasementDocs} collection,
   * returning up to {@value #PAGE_SIZE} results per page. Only the first page
   * of each matching document is shown as a card; clicking a card navigates
   * to {@code /easement} to view all pages.
   *
   * @param q     optional query string; supports {@code AND}, {@code OR},
   *              wildcards ({@code parcel*}), and phrases ({@code "right of way"})
   * @param page  1-based page number; defaults to 1
   * @param model Spring MVC model populated for the Thymeleaf template
   * @return the logical view name {@code "search"}
   */
  @GetMapping("/search")
  public String search(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "1") int page,
      Model model) {

    model.addAttribute("query", q != null ? q : "");
    List<PageCard> cards = new LinkedList<>();
    int totalCount = 0;
    int totalPages = 0;

    if (q != null && !q.isBlank()) {
      if (page < 1) {
        page = 1;
      }

      String rql = buildRql(q);
      log.info("RQL: {} (page {})", rql, page);

      Reference<QueryStatistics> statsRef = new Reference<>();
      List<EasementDoc> docs = session.advanced()
          .rawQuery(EasementDoc.class, rql)
          .statistics(statsRef)
          .skip((page - 1) * PAGE_SIZE)
          .take(PAGE_SIZE)
          .toList();

      totalCount = statsRef.value.getTotalResults();
      totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);

      if (page > totalPages && totalPages > 0) {
        page = totalPages;
      }

      log.info("Query '{}' — {} total, page {}/{}", q, totalCount, page, totalPages);

      for (EasementDoc doc : docs) {
        cards.add(new PageCard(
            doc.getId(),
            doc.getFilename(),
            "page-1.png",
            1,
            doc.getPageCount()));
      }
    }

    int pageStart = totalCount == 0 ? 0 : (page - 1) * PAGE_SIZE + 1;
    int pageEnd = Math.min(page * PAGE_SIZE, totalCount);

    model.addAttribute("cards", cards);
    model.addAttribute("currentPage", page);
    model.addAttribute("totalPages", totalPages);
    model.addAttribute("totalCount", totalCount);
    model.addAttribute("pageStart", pageStart);
    model.addAttribute("pageEnd", pageEnd);
    model.addAttribute("pageNumbers", computePageNumbers(page, totalPages));
    return "search";
  }

  /**
   * Renders the document view page showing all pages of a single
   * {@link EasementDoc} as a card grid. Returns a redirect to
   * {@code /search} if the document ID is not found.
   *
   * @param docId the RavenDB document ID (original PDF filename)
   * @param model Spring MVC model populated for the Thymeleaf template
   * @return the logical view name {@code "easement"}, or a redirect
   */
  @GetMapping("/easement")
  public String document(
      @RequestParam String docId,
      Model model) {

    EasementDoc doc = session.load(EasementDoc.class, docId);

    if (doc == null) {
      log.warn("Document not found: {}", docId);
      return "redirect:/search";
    }

    List<PageCard> pages = new LinkedList<>();
    for (int i = 1; i <= doc.getPageCount(); i++) {
      pages.add(new PageCard(
          doc.getId(),
          doc.getFilename(),
          "page-" + i + ".png",
          i,
          doc.getPageCount()));
    }

    model.addAttribute("doc", doc);
    model.addAttribute("pages", pages);
    return "easement";
  }

  /**
   * Computes the list of page-number buttons to display in the pagination bar.
   * Returns at most 7 entries; {@code -1} is used as a sentinel for an
   * ellipsis ({@code …}) between non-adjacent ranges.
   *
   * <ul>
   *   <li>Up to 7 pages: show all.</li>
   *   <li>Near the start: 1 2 3 4 5 … N</li>
   *   <li>Near the end: 1 … N-4 N-3 N-2 N-1 N</li>
   *   <li>Middle: 1 … cur-1 cur cur+1 … N</li>
   * </ul>
   *
   * @param currentPage 1-based current page number
   * @param totalPages  total number of pages
   * @return ordered list of page numbers and {@code -1} ellipsis sentinels
   */
  static List<Integer> computePageNumbers(int currentPage, int totalPages) {

    List<Integer> nums = new LinkedList<>();

    if (totalPages <= 7) {
      for (int i = 1; i <= totalPages; i++) {
        nums.add(i);
      }
    } else if (currentPage <= 4) {
      for (int i = 1; i <= 5; i++) {
        nums.add(i);
      }
      nums.add(-1);
      nums.add(totalPages);
    } else if (currentPage >= totalPages - 3) {
      nums.add(1);
      nums.add(-1);
      for (int i = totalPages - 4; i <= totalPages; i++) {
        nums.add(i);
      }
    } else {
      nums.add(1);
      nums.add(-1);
      nums.add(currentPage - 1);
      nums.add(currentPage);
      nums.add(currentPage + 1);
      nums.add(-1);
      nums.add(totalPages);
    }

    return nums;
  }

  /**
   * Translates a user-entered query into a RavenDB RQL {@code where} clause.
   *
   * <p>RavenDB's Corax engine does not parse {@code AND}/{@code OR} inside a
   * single {@code search()} call — they are treated as stopwords. Boolean
   * operators must be expressed as separate {@code search()} clauses joined by
   * RQL {@code and}/{@code or}. This method splits the user's query on
   * uppercase {@code AND}/{@code OR} operators and builds the clause accordingly.
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code ardmore} →
   *       {@code from EasementDocs where search(lines, 'ardmore')}</li>
   *   <li>{@code easement AND ardmore} →
   *       {@code from EasementDocs where search(lines, 'easement') and search(lines, 'ardmore')}</li>
   *   <li>{@code "right of way" OR grant} →
   *       {@code from EasementDocs where search(lines, '"right of way"') or search(lines, 'grant')}</li>
   * </ul>
   *
   * @param q the raw query string entered by the user
   * @return a complete RQL query string
   */
  static String buildRql(String q) {

    List<String> terms = new LinkedList<>();
    List<String> ops = new LinkedList<>();

    Matcher m = BOOL_OP.matcher(q);
    int last = 0;
    while (m.find()) {
      terms.add(q.substring(last, m.start()).trim());
      ops.add(m.group(1).toLowerCase());
      last = m.end();
    }
    terms.add(q.substring(last).trim());

    StringBuilder rql = new StringBuilder("from EasementDocs where ");
    for (int i = 0; i < terms.size(); i++) {
      if (i > 0) {
        rql.append(' ').append(ops.get(i - 1)).append(' ');
      }
      rql.append("search(lines, '")
          .append(terms.get(i).replace("'", "''"))
          .append("')");
    }
    return rql.toString();
  }

  /**
   * Streams a single page-image attachment from RavenDB so the browser can
   * render it inside an {@code <img>} tag. Returns 404 if the document or
   * attachment does not exist.
   *
   * @param docId the RavenDB document ID (original PDF filename)
   * @param name  the attachment name, e.g. {@code page-1.png}
   * @return the raw image bytes with the correct {@code Content-Type}, or 404
   * @throws IOException if reading the attachment stream fails
   */
  @GetMapping("/api/easement/attachment")
  @ResponseBody
  public ResponseEntity<byte[]> getAttachment(
      @RequestParam String docId,
      @RequestParam String name)
      throws IOException {

    try (CloseableAttachmentResult result =
        session.advanced().attachments().get(docId, name)) {

      if (result == null) {
        return ResponseEntity.notFound().build();
      }

      byte[] bytes = result.getData().readAllBytes();
      MediaType contentType =
          MediaType.parseMediaType(result.getDetails().getContentType());

      return ResponseEntity.ok()
          .contentType(contentType)
          .body(bytes);

    } catch (Exception e) {
      log.warn("Attachment not found: docId='{}' name='{}': {}",
          docId, name, e.getMessage());
      return ResponseEntity.notFound().build();
    }
  }

}
