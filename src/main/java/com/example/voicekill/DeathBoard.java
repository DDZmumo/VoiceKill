package com.example.voicekill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 言多必失榜：统计每个玩家因为说错话被处决的次数。
 * 只在服务端逻辑线程写，网页线程读快照，所以这里加同步兜底。
 */
public final class DeathBoard {

    public record Entry(String name, int count) {
    }

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int total = 0;

    public synchronized void record(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        counts.merge(playerName, 1, Integer::sum);
        total++;
    }

    public synchronized List<Entry> top(int n) {
        List<Entry> list = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            list.add(new Entry(e.getKey(), e.getValue()));
        }
        list.sort(Comparator
                .comparingInt(Entry::count).reversed()
                .thenComparing(Entry::name));
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    public synchronized int total() {
        return total;
    }

    public synchronized void reset() {
        counts.clear();
        total = 0;
    }
}
