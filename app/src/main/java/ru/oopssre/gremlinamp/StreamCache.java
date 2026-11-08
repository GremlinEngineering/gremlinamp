package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class StreamCache {
    static final int LIMIT = 30_000_000;
    record Usage(int bytes, int pinned, int entries) {}
    interface Read { byte[] read() throws Exception; }
    static final class Full extends IOException {
        Full() { super("Трек или его зависимости превышают доступный буфер ТВ"); }
    }

    private static final class Block {
        final int size;
        int pins = 1;
        boolean played, discard;
        byte[] data;
        Block(int size) { this.size = size; }
    }

    private final LinkedHashMap<String, Block> blocks = new LinkedHashMap<>(16, .75f, true);
    private final int limit;
    private int used;

    StreamCache(int limit) { this.limit = limit; }

    final class Track implements AutoCloseable {
        private final Map<String, Block> assets = new HashMap<>();
        private final boolean foreground;
        private boolean closed;

        Track(boolean foreground) { this.foreground = foreground; }

        byte[] get(String key, long size, Read read) throws Exception {
            if (size <= 0 || size > limit) throw new Full();
            Block block;
            synchronized (StreamCache.this) {
                if (closed) throw new IllegalStateException("Closed track");
                block = assets.get(key);
                if (block != null) {
                    if (block.size != size) throw new IOException("Неверный размер трека");
                    return block.data;
                }
                while ((block = blocks.get(key)) != null && block.data == null) StreamCache.this.wait();
                if (block != null) {
                    if (block.size != size) throw new IOException("Неверный размер трека");
                    block.pins++;
                    assets.put(key, block);
                    return block.data;
                }
                evict((int) size, foreground);
                if (size > limit - used) throw new Full();
                block = new Block((int) size);
                blocks.put(key, block);
                assets.put(key, block);
                used += block.size;
            }
            try {
                byte[] data = read.read();
                if (data.length != size) throw new IOException("Неверный размер трека");
                synchronized (StreamCache.this) {
                    block.data = data;
                    StreamCache.this.notifyAll();
                }
                return data;
            } catch (Exception | Error e) {
                synchronized (StreamCache.this) {
                    assets.remove(key);
                    blocks.remove(key);
                    used -= block.size;
                    StreamCache.this.notifyAll();
                }
                throw e;
            }
        }

        @Override public void close() {
            synchronized (StreamCache.this) {
                if (closed) return;
                closed = true;
                for (Map.Entry<String, Block> entry : assets.entrySet()) {
                    Block block = entry.getValue();
                    block.pins--;
                    block.played |= foreground;
                    if (block.pins == 0 && block.discard) {
                        blocks.remove(entry.getKey());
                        used -= block.size;
                    }
                }
                assets.clear();
                StreamCache.this.notifyAll();
            }
        }
    }

    Track track(boolean foreground) { return new Track(foreground); }

    private void evict(int size, boolean includeUpcoming) {
        Iterator<Block> it = blocks.values().iterator();
        while (size > limit - used && it.hasNext()) {
            Block block = it.next();
            if (block.pins == 0 && (block.played || includeUpcoming)) {
                used -= block.size;
                it.remove();
            }
        }
    }

    synchronized Usage usage() {
        int pinned = 0;
        for (Block block : blocks.values()) if (block.pins > 0) pinned += block.size;
        return new Usage(used, pinned, blocks.size());
    }

    synchronized void clear() {
        Iterator<Block> it = blocks.values().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (block.pins > 0) block.discard = true;
            else {
                used -= block.size;
                it.remove();
            }
        }
    }
}
