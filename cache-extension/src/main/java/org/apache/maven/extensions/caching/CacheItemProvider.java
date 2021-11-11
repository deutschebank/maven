package org.apache.maven.extensions.caching;

import org.codehaus.plexus.component.annotations.Component;

import java.util.concurrent.ConcurrentMap;

@Component( role = CacheItemProvider.class )
public interface CacheItemProvider {

    <K, V> ConcurrentMap<K, V> getCache(String cacheName, Class<K> key, Class<V> value);

}
