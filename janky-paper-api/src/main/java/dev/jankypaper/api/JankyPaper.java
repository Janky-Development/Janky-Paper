package dev.jankypaper.api;

/**
 * Entry point for Janky Paper specific API.
 *
 * <p>Classes in this module are compiled together with the (patched) Paper API
 * from {@code paper-api}, so plugins depending on Janky Paper get both.</p>
 */
public final class JankyPaper {

    /** Human readable name of this server software. */
    public static final String NAME = "Janky Paper";

    private JankyPaper() {
    }
}
