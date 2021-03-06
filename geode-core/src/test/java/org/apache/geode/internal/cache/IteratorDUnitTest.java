/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache;

import static org.junit.Assert.*;

import java.util.Iterator;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.SerializableCallable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.DistributedTest;

/**
 * Test that keys iterator do not returned keys with removed token as its values
 *
 */
@Category(DistributedTest.class)
public class IteratorDUnitTest extends JUnit4CacheTestCase {

  public IteratorDUnitTest() {
    super();
  }

  @Test
  public void testKeysIteratorOnLR() throws Exception {
    final String regionName = getUniqueName();
    Region r = getGemfireCache().createRegionFactory(RegionShortcut.REPLICATE).create(regionName);
    r.put("key", "value");
    r.put("key2", "value2");
    r.put("key3", "value3");
    LocalRegion lr = (LocalRegion) r;
    // simulate a removed key
    // region.getRegionMap().getEntry("key")._setValue(Token.REMOVED_PHASE1);
    lr.getRegionMap().getEntry("key").setValue(lr, Token.REMOVED_PHASE1);
    Iterator it = r.keySet().iterator();
    int numKeys = 0;
    while (it.hasNext()) {
      it.next();
      numKeys++;
    }
    assertEquals(2, numKeys);
  }

  @Test
  public void testKeysIteratorOnPR() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    final String regionName = getUniqueName();

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        getGemfireCache().createRegionFactory(RegionShortcut.PARTITION_PROXY).create(regionName);
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region r =
            getGemfireCache().createRegionFactory(RegionShortcut.PARTITION).create(regionName);
        r.put("key", "value");
        r.put("key2", "value2");
        r.put("key3", "value3");
        PartitionedRegion pr = (PartitionedRegion) r;
        BucketRegion br = pr.getBucketRegion("key");
        assertNotNull(br);
        // simulate a removed key
        br.getRegionMap().getEntry("key").setValue(pr, Token.REMOVED_PHASE1);
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region r = getGemfireCache().getRegion(regionName);
        Iterator it = r.keySet().iterator();
        int numKeys = 0;
        while (it.hasNext()) {
          it.next();
          numKeys++;
        }
        assertEquals(2, numKeys);
        return null;
      }
    });
  }
}
