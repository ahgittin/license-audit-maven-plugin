package org.heneveld.maven.license_audit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class SimpleMultiMap<K,V> {

    Map<K,Set<V>> real = new LinkedHashMap<K,Set<V>>(); 
    
    public void put(K key, V val) {
        synchronized (real) {
            Set<V> vv = real.get(key);
            if (vv==null) {
                vv = new LinkedHashSet<V>();
                real.put(key, vv);
            }
            vv.add(val);
        }
    }
    
    public Set<V> get(K key) {
        synchronized (real) {
            return real.get(key);
        }
    }

    public Set<K> keySet() {
        return real.keySet();
    }

    public boolean isEmpty() {
        return real.isEmpty();
    }

    public Set<Entry<K, Set<V>>> entrySet() {
        return real.entrySet();
    }
    
}
