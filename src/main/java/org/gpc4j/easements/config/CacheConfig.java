package org.gpc4j.easements.config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

/**
 * Configures two Caffeine-backed caches:
 *
 * <ul>
 *   <li>{@code attachments} — weight-bounded at 1 GiB; each entry is weighed
 *       by the length of its {@code byte[]} body; entries expire 1 hour after
 *       write.</li>
 *   <li>{@code searchResults} — entry-count-bounded at 1 000 results; entries
 *       expire 5 minutes after write.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /**
   * 1 GiB expressed in bytes.
   */
  private static final long ATTACHMENT_MAX_WEIGHT_BYTES = 1_073_741_824L;

  private static final int SEARCH_MAX_ENTRIES = 1_000;

  /**
   * Weigher that returns the byte length of a cached
   * {@link ResponseEntity}{@code <byte[]>} value.
   *
   * @return the weigher used by the attachments cache
   */
  @Bean
  public Weigher<String, ResponseEntity<byte[]>> attachmentWeigher() {

    return (key, value) -> {
      byte[] body = value.getBody();
      return body != null ? body.length : 1;
    };
  }


  /**
   * {@link CacheManager} backed by two independently configured
   * {@link CaffeineCache} instances.
   *
   * @param weigher the weigher used to measure attachment cache entries
   * @return the configured cache manager
   */
  @Bean
  @SuppressWarnings("unchecked")
  public CacheManager cacheManager(Weigher<String, ResponseEntity<byte[]>> weigher) {

    var attachmentCache = Caffeine.newBuilder()
      .maximumWeight(ATTACHMENT_MAX_WEIGHT_BYTES)
      .weigher((Weigher<Object, Object>) (Weigher<?, ?>) weigher)
      .expireAfterWrite(1, TimeUnit.HOURS).<Object, Object>build();
    var attachments = new CaffeineCache("attachments", attachmentCache);

    var searchCache = Caffeine.newBuilder().maximumSize(SEARCH_MAX_ENTRIES)
      .expireAfterWrite(5, TimeUnit.MINUTES).<Object, Object>build();
    var searchResults = new CaffeineCache("searchResults", searchCache);

    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(List.of(attachments, searchResults));
    return manager;
  }

}
