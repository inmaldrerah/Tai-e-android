/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.dfa.fact;

import pascal.taie.util.collection.MapUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class MapFact<K, V> {

    protected final Map<K, V> map;

    public MapFact(Map<K, V> map) {
        this.map = MapUtils.newHybridMap(map);
    }

    public MapFact() {
        this(Collections.emptyMap());
    }

    /**
     * @return the value to which the specified key is mapped,
     * or null if this map contains no mapping for the key.
     */
    public V get(Object key) {
        //noinspection SuspiciousMethodCalls
        return map.get(key);
    }

    /**
     * Updates the key-value mapping in this fact.
     * @return  if the update changes this fact.
     */
    public boolean update(K key, V value) {
        return !Objects.equals(map.put(key, value), value);
    }

    /**
     * Copies the content from given fact to this fact.
     * @return if the copy changes this fact.
     */
    public boolean copyFrom(MapFact<K, V> fact) {
        boolean changed = false;
        for (Map.Entry<K, V> entry : fact.map.entrySet()) {
            changed |= update(entry.getKey(), entry.getValue());
        }
        return changed;
    }

    /**
     * Creates a duplication of this fact.
     */
    public MapFact<K, V> duplicate() {
        return new MapFact<>(this.map);
    }

    /**
     * Clears all content in this fact.
     */
    public void clear() {
        map.clear();
    }

    public Set<K> keySet() {
        return map.keySet();
    }

    public Stream<Map.Entry<K, V>> entries() {
        return map.entrySet().stream();
    }

    public void forEach(BiConsumer<K, V> action) {
        map.forEach(action);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MapFact)) {
            return false;
        }
        MapFact<?, ?> that = (MapFact<?, ?>) o;
        return map.equals(that.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.toString();
    }
}