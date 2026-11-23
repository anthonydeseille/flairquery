package io.github.anthonydeseille.flairquery.collector.source;

/**
 * Read position in one log file. Files are reused (postgresql-%H.json, truncated on rotation): {@code head}
 * (the beginning of the first line) tells whether the content is still the one the offset refers to.
 */
public record LogCursor(String fileName, long offset, String head) { }
