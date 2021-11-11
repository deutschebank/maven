package org.apache.maven.extensions.caching;

import org.codehaus.plexus.component.annotations.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component( role = CacheItemProvider.class )
public class DefaultCacheItemProvider implements CacheItemProvider {

    private final ConcurrentMap<String, ConcurrentMap<?, ?>> cache = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ConcurrentMap<K, V> getCache(String cacheName, Class<K> key, Class<V> value) {
        return (ConcurrentMap<K, V>) cache.computeIfAbsent(cacheName + ":" + key.getName() + ":" + value.getName(),
                k -> new ConcurrentHashMap<>());
    }
}
