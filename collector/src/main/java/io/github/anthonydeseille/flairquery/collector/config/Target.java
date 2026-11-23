package io.github.anthonydeseille.flairquery.collector.config;

/**
 * An observed PostgreSQL database, read with a read-only role (fq_reader). Phase 2 handles a single target;
 * the model and the storage are already keyed by target name for the multi-target evolution.
 */
public record Target(String name, String jdbcUrl, String user, String password) {

    @Override
    public String toString() {
        return "Target[" + name + " " + jdbcUrl + " as " + user + "]"; // never the password
    }
}
