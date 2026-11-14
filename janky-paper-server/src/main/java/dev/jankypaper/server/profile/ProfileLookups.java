/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.profile;

import com.mojang.authlib.exceptions.MinecraftClientException;
import com.mojang.authlib.exceptions.MinecraftClientHttpException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guards the signed profile lookups ({@code sessionserver.mojang.com/session/minecraft/profile/<uuid>})
 * behind {@code PlayerProfile#update()} and {@code PlayerProfile#complete()}.
 *
 * <p>authlib never caches these and swallows every failure (a WARN with a full stack trace, then
 * {@code null}), so plugins can't tell a rate limit from a missing profile. A plugin refreshing skull
 * or GUI heads ends up asking for the same handful of UUIDs over and over: Mojang answers HTTP 429
 * and the console fills with stack traces. This class:</p>
 * <ul>
 *   <li>caches results per UUID: found profiles for {@link #HIT_TTL_MS}, missing profiles for
 *       {@link #MISS_TTL_MS}, other failures for {@link #ERROR_TTL_MS};</li>
 *   <li>merges concurrent lookups of the same UUID into one request;</li>
 *   <li>after a 429, sends no requests at all for a cooldown (60s, doubling to 10 min while 429s
 *       continue, reset by the next success). During the cooldown it returns the last known profile,
 *       or {@code null};</li>
 *   <li>logs failures as one line, and the rate limit once per cooldown.</li>
 * </ul>
 *
 * @param <R> the authlib profile result type of this Minecraft version
 */
public final class ProfileLookups<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger("ProfileLookups");

    static final long HIT_TTL_MS = 5 * 60_000L;
    static final long MISS_TTL_MS = 10 * 60_000L;
    static final long ERROR_TTL_MS = 30_000L;
    static final long COOLDOWN_MIN_MS = 60_000L;
    static final long COOLDOWN_MAX_MS = 10 * 60_000L;
    private static final int MAX_ENTRIES = 4096;
    private static final int TOO_MANY_REQUESTS = 429;

    @FunctionalInterface
    public interface Fetcher<R> {
        /**
         * Asks Mojang for the profile. Returns {@code null} if it doesn't exist; throws authlib's
         * {@link MinecraftClientException} on a failed request.
         */
        @Nullable R fetch(UUID id);
    }

    private record Entry<R>(@Nullable R result, long expiresAt) {
    }

    private final Map<UUID, Entry<R>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<@Nullable R>> inFlight = new ConcurrentHashMap<>();
    private final UnaryOperator<R> copy;
    private volatile long cooldownUntil;
    private volatile long cooldownMs;

    /**
     * @param copy returns a copy safe to hand to a caller - callers may mutate the profile they get
     */
    public ProfileLookups(final UnaryOperator<R> copy) {
        this.copy = copy;
    }

    public @Nullable R fetch(final UUID id, final Fetcher<R> fetcher) {
        final long now = System.currentTimeMillis();
        final Entry<R> cached = this.cache.get(id);
        if (cached != null && now < cached.expiresAt()) {
            return this.copyOf(cached.result());
        }
        if (now < this.cooldownUntil) {
            return cached != null ? this.copyOf(cached.result()) : null; // stale beats nothing
        }

        final CompletableFuture<@Nullable R> mine = new CompletableFuture<>();
        final CompletableFuture<@Nullable R> running = this.inFlight.putIfAbsent(id, mine);
        if (running != null) {
            return this.copyOf(running.join()); // another thread is already asking Mojang for this UUID
        }
        @Nullable R result = cached != null ? cached.result() : null;
        try {
            result = this.load(id, fetcher, cached);
            return this.copyOf(result);
        } finally {
            this.inFlight.remove(id, mine);
            mine.complete(result);
        }
    }

    private @Nullable R load(final UUID id, final Fetcher<R> fetcher, final @Nullable Entry<R> cached) {
        try {
            final R result = fetcher.fetch(id);
            this.cooldownMs = 0L;
            this.put(id, result, result != null ? HIT_TTL_MS : MISS_TTL_MS);
            return result;
        } catch (final MinecraftClientHttpException e) {
            if (e.getStatus() == TOO_MANY_REQUESTS) {
                this.rateLimited();
            } else {
                LOGGER.warn("Couldn't look up profile properties for {}: HTTP {}", id, e.getStatus());
                this.put(id, cached != null ? cached.result() : null, ERROR_TTL_MS);
            }
        } catch (final MinecraftClientException | IllegalArgumentException e) {
            LOGGER.warn("Couldn't look up profile properties for {}: {}", id, e.getMessage());
            this.put(id, cached != null ? cached.result() : null, ERROR_TTL_MS);
        }
        return cached != null ? cached.result() : null;
    }

    private synchronized void rateLimited() {
        final long now = System.currentTimeMillis();
        if (now < this.cooldownUntil) {
            return; // a request that started before the cooldown - already counted
        }
        final long next = this.cooldownMs == 0L ? COOLDOWN_MIN_MS : Math.min(this.cooldownMs * 2, COOLDOWN_MAX_MS);
        this.cooldownMs = next;
        this.cooldownUntil = now + next;
        LOGGER.warn("Mojang is rate limiting profile lookups (HTTP 429) - pausing them for {}s; plugins get cached profiles until then", next / 1000);
    }

    private void put(final UUID id, final @Nullable R result, final long ttl) {
        final long now = System.currentTimeMillis();
        if (this.cache.size() >= MAX_ENTRIES) {
            for (final Iterator<Entry<R>> it = this.cache.values().iterator(); it.hasNext(); ) {
                if (now >= it.next().expiresAt()) {
                    it.remove();
                }
            }
            if (this.cache.size() >= MAX_ENTRIES) {
                this.cache.clear();
            }
        }
        this.cache.put(id, new Entry<>(result, now + ttl));
    }

    private @Nullable R copyOf(final @Nullable R result) {
        return result != null ? this.copy.apply(result) : null;
    }
}
