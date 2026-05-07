package com.devoxx.lowlatency.examples.capstone;

/**
 * BACKS SLIDE: "Production architecture — exchange-core style"
 * PATTERN: Truth #2 (single-threaded shards) — symbol-id routing for the matching engine
 * MECHANISM: A 16-bit mask on symbolId followed by a modulo. Stateless, branchless,
 *            allocation-free. The router runs on the Disruptor handler thread, so
 *            anything we do here lands on the critical path: keep it boring.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
 * SEE ALSO: examples/src/main/java/com/devoxx/lowlatency/examples/concurrency/ShardedAccountServiceExample.java
 */
public final class ShardRouter {

    private final int shardCount;

    public ShardRouter(int shardCount) {
        // 3 shards mirror slide 40's reference box — one shard per pinned core.
        // Three is not a power of two, so the modulo below is a real divide
        // rather than a single AND. That trade-off is intentional — the slide
        // story is "one thread per core", not "fastest possible routing".
        // With a power-of-two shardCount the JIT collapses this to:
        //     shard = (int)(symbolId & (N - 1));
        //
        // Why symbolId and not userId: a symbol's order book MUST live on
        // exactly one thread, otherwise different shards see stale views of
        // the same book and crossing orders never match. Slide 18's
        // ShardedAccountService partitions *accounts* by user — that's the
        // right axis for a balance service. Slide 40's matching engine, and
        // exchange-core in production (slide 19), partition by symbol.
        this.shardCount = shardCount;
    }

    public int shardForSymbol(long symbolId) {
        // Mask to 16 bits, then modulo. With a power-of-two shardCount the
        // modulo collapses to a single AND once N is constant-folded by the
        // JIT; with N = 3 the bounded input keeps branch prediction stable
        // on the hot path.
        return ((int) (symbolId & 0xFFFFL)) % shardCount;
    }

    public int shardCount() {
        return shardCount;
    }
}
