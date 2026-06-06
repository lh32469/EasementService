package org.gpc4j.easements.config;

import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

/**
 * Configures the Caffeine-backed cache for RavenDB attachment bytes.
 *
 * <p>The {@code attachments} cache is weight-bounded at 1 GiB so that
 * accumulated page-image bytes never exceed that threshold regardless of
 * entry count. Each entry is weighed by the length of its {@code byte[]}
 * body; entries expire one hour after they are written.
 */
@Configuration
public class CacheConfig {

  /** 1 GiB expressed in bytes. */
  private static final long MAX_WEIGHT_BYTES = 1_073_741_824L;

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
   * {@link CacheManager} for the {@code attachments} cache, weight-capped
   * at 1 GiB with a 1-hour write expiry.
   *
   * @param weigher the weigher used to measure each cached entry
   * @return the configured cache manager
   */
  @Bean
  @SuppressWarnings("unchecked")
  public CacheManager cacheManager(Weigher<String, ResponseEntity<byte[]>> weigher) {

    Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
      .maximumWeight(MAX_WEIGHT_BYTES)
      .weigher((Weigher<Object, Object>) (Weigher<?, ?>) weigher)
      .expireAfterWrite(1, TimeUnit.HOURS);

    CaffeineCacheManager manager = new CaffeineCacheManager("attachments");
    manager.setCaffeine(caffeine);
    return manager;
  }

}
