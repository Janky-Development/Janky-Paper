/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.bot;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.TicketOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.TicketType;

/**
 * One shared set of player chunk tickets for all bots in a world, reference counted.
 *
 * <p>Moonrise's player chunk loader gives every player its own ticket (keyed by the loader's id) on every chunk in its
 * load radius. With a crowd of bots that is one ticket per bot on each chunk under the crowd - 10,000 bots standing in
 * the same few chunks put ~10,000 tickets on each of them. The ticket set of a chunk is a sorted array, so every bot
 * crossing a chunk border inserted into / removed from 10,000-entry arrays, and once any of those chunks held an
 * expiring ticket (the delayed unload ticket a bot leaves behind when it walks off) the ticket expiry pass walked all
 * of them every tick.</p>
 *
 * <p>Bots' loaders run their ticket operations through here instead: a {@code PLAYER_TICKET} at a level is added once
 * when the first bot needs it and removed when the last one lets go, all under one identifier ({@link #BOT_ID}).
 * Delayed unload tickets expire on their own, so they are passed through under the shared identifier - adding one again
 * just refreshes its timeout. Chunks end up exactly as loaded / ticking as they were with per-bot tickets.</p>
 *
 * <p>Main thread only (the player chunk loader runs there).</p>
 */
public final class BotChunkTickets {

    /** The identifier of every bot ticket. Loader ids start at 1, so this never collides with a real player's. */
    public static final Long BOT_ID = Long.valueOf(-1L);

    private final Int2ObjectOpenHashMap<Long2IntOpenHashMap> refsByLevel = new Int2ObjectOpenHashMap<>();
    private final List<TicketOperation<?, ?>> out = new ArrayList<>();

    /** Translates one bot loader's batch of ticket operations; the returned list is reused by the next call. */
    public List<TicketOperation<?, ?>> translate(final Collection<TicketOperation<?, ?>> operations) {
        this.out.clear();
        for (final TicketOperation<?, ?> operation : operations) {
            switch (operation.op()) {
                case ADD -> this.add(operation.chunkCoord(), operation.ticketType(), operation.ticketLevel());
                case REMOVE -> this.remove(operation.chunkCoord(), operation.ticketType(), operation.ticketLevel());
                case ADD_AND_REMOVE -> {
                    // add first, so the chunk's level never dips in between
                    this.add(operation.chunkCoord(), operation.ticketType(), operation.ticketLevel());
                    this.remove(operation.chunkCoord(), operation.ticketType2(), operation.ticketLevel2());
                }
                default -> this.out.add(operation); // the player loader does not use the others: keep their meaning
            }
        }
        return this.out;
    }

    private Long2IntOpenHashMap refs(final int level) {
        Long2IntOpenHashMap refs = this.refsByLevel.get(level);
        if (refs == null) {
            this.refsByLevel.put(level, refs = new Long2IntOpenHashMap());
        }
        return refs;
    }

    private void add(final long chunk, final TicketType type, final int level) {
        if (type == RegionizedPlayerChunkLoader.PLAYER_TICKET) {
            if (this.refs(level).addTo(chunk, 1) == 0) {
                this.out.add(TicketOperation.addOp(chunk, type, level, BOT_ID));
            }
            return;
        }
        // the delayed unload ticket expires by itself; re-adding refreshes its timeout
        this.out.add(TicketOperation.addOp(chunk, type, level, BOT_ID));
    }

    private void remove(final long chunk, final TicketType type, final int level) {
        if (type == RegionizedPlayerChunkLoader.PLAYER_TICKET) {
            final Long2IntOpenHashMap refs = this.refs(level);
            final int previous = refs.addTo(chunk, -1);
            if (previous <= 1) {
                refs.remove(chunk);
                if (previous == 1) {
                    this.out.add(TicketOperation.removeOp(chunk, type, level, BOT_ID));
                }
            }
            return;
        }
        this.out.add(TicketOperation.removeOp(chunk, type, level, BOT_ID));
    }

    /** Chunks currently held by at least one bot ticket, summed over levels (for diagnostics). */
    public int heldChunkLevels() {
        int total = 0;
        for (final Long2IntOpenHashMap refs : this.refsByLevel.values()) {
            total += refs.size();
        }
        return total;
    }
}
