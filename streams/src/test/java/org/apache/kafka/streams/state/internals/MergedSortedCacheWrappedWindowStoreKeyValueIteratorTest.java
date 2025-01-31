/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import org.apache.kafka.streams.state.StateSerdes;
import org.apache.kafka.streams.state.internals.MergedSortedCacheWindowStoreKeyValueIterator.StoreKeyToWindowKey;
import org.apache.kafka.streams.state.internals.MergedSortedCacheWindowStoreKeyValueIterator.WindowKeyToBytes;
import org.apache.kafka.streams.state.internals.PrefixedWindowKeySchemas.KeyFirstWindowKeySchema;
import org.apache.kafka.streams.state.internals.PrefixedWindowKeySchemas.TimeFirstWindowKeySchema;
import org.apache.kafka.test.KeyValueIteratorStub;

import java.util.Collections;
import java.util.Iterator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MergedSortedCacheWrappedWindowStoreKeyValueIteratorTest {

    @FunctionalInterface
    private interface StoreKeySerializer<K> {
        Bytes serialize(final Windowed<K> key, final int seq, final StateSerdes<K, ?> serdes);
    }

    private static final SegmentedCacheFunction SINGLE_SEGMENT_CACHE_FUNCTION = new SegmentedCacheFunction(null, -1) {
        @Override
        public long segmentId(final Bytes key) {
            return 0;
        }
    };

    private static final int WINDOW_SIZE = 10;

    private final String storeKey = "a";
    private final String cacheKey = "b";

    private final TimeWindow storeWindow = new TimeWindow(0, 1);
    private final Iterator<KeyValue<Windowed<Bytes>, byte[]>> storeKvs = Collections.singleton(
        KeyValue.pair(new Windowed<>(Bytes.wrap(storeKey.getBytes()), storeWindow), storeKey.getBytes())).iterator();
    private final TimeWindow cacheWindow = new TimeWindow(10, 20);
    private Iterator<KeyValue<Bytes, LRUCacheEntry>> cacheKvs;
    private final Deserializer<String> deserializer = Serdes.String().deserializer();

    private StoreKeySerializer<String> storeKeySerializer;
    private StoreKeyToWindowKey storeKeyToWindowKey;
    private WindowKeyToBytes windowKeyToBytes;

    private enum SchemaType {
        WINDOW_KEY_SCHEMA,
        KEY_FIRST_SCHEMA,
        TIME_FIRST_SCHEMA
    }

    public void setUp(final SchemaType schemaType) {
        switch (schemaType) {
            case KEY_FIRST_SCHEMA:
                storeKeySerializer = KeyFirstWindowKeySchema::toStoreKeyBinary;
                storeKeyToWindowKey = KeyFirstWindowKeySchema::fromStoreKey;
                windowKeyToBytes = KeyFirstWindowKeySchema::toStoreKeyBinary;
                break;
            case WINDOW_KEY_SCHEMA:
                storeKeySerializer = WindowKeySchema::toStoreKeyBinary;
                storeKeyToWindowKey = WindowKeySchema::fromStoreKey;
                windowKeyToBytes = WindowKeySchema::toStoreKeyBinary;
                break;
            case TIME_FIRST_SCHEMA:
                storeKeySerializer = TimeFirstWindowKeySchema::toStoreKeyBinary;
                storeKeyToWindowKey = TimeFirstWindowKeySchema::fromStoreKey;
                windowKeyToBytes = TimeFirstWindowKeySchema::toStoreKeyBinary;
                break;
            default:
                throw new IllegalStateException("Unknown schemaType: " + schemaType);
        }
        cacheKvs = Collections.singleton(
            KeyValue.pair(
                SINGLE_SEGMENT_CACHE_FUNCTION.cacheKey(storeKeySerializer.serialize(
                    new Windowed<>(cacheKey, cacheWindow), 0, new StateSerdes<>("dummy", Serdes.String(), Serdes.ByteArray()))
                ),
                new LRUCacheEntry(cacheKey.getBytes())
            )
        ).iterator();
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldHaveNextFromStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), false);
        assertTrue(mergeIterator.hasNext());
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldHaveNextFromReverseStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), true);
        assertTrue(mergeIterator.hasNext());
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldGetNextFromStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), false);
        assertThat(convertKeyValuePair(mergeIterator.next()), equalTo(KeyValue.pair(new Windowed<>(storeKey, storeWindow), storeKey)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldGetNextFromReverseStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), true);
        assertThat(convertKeyValuePair(mergeIterator.next()), equalTo(KeyValue.pair(new Windowed<>(storeKey, storeWindow), storeKey)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldPeekNextKeyFromStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), false);
        assertThat(convertWindowedKey(mergeIterator.peekNextKey()), equalTo(new Windowed<>(storeKey, storeWindow)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldPeekNextKeyFromReverseStore(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(storeKvs, Collections.emptyIterator(), true);
        assertThat(convertWindowedKey(mergeIterator.peekNextKey()), equalTo(new Windowed<>(storeKey, storeWindow)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldHaveNextFromCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, false);
        assertTrue(mergeIterator.hasNext());
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldHaveNextFromReverseCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, true);
        assertTrue(mergeIterator.hasNext());
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldGetNextFromCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, false);
        assertThat(convertKeyValuePair(mergeIterator.next()), equalTo(KeyValue.pair(new Windowed<>(cacheKey, cacheWindow), cacheKey)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldGetNextFromReverseCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, true);
        assertThat(convertKeyValuePair(mergeIterator.next()), equalTo(KeyValue.pair(new Windowed<>(cacheKey, cacheWindow), cacheKey)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldPeekNextKeyFromCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, false);
        assertThat(convertWindowedKey(mergeIterator.peekNextKey()), equalTo(new Windowed<>(cacheKey, cacheWindow)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldPeekNextKeyFromReverseCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator mergeIterator =
            createIterator(Collections.emptyIterator(), cacheKvs, true);
        assertThat(convertWindowedKey(mergeIterator.peekNextKey()), equalTo(new Windowed<>(cacheKey, cacheWindow)));
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldIterateBothStoreAndCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator iterator = createIterator(storeKvs, cacheKvs, true);
        assertThat(convertKeyValuePair(iterator.next()), equalTo(KeyValue.pair(new Windowed<>(storeKey, storeWindow), storeKey)));
        assertThat(convertKeyValuePair(iterator.next()), equalTo(KeyValue.pair(new Windowed<>(cacheKey, cacheWindow), cacheKey)));
        assertFalse(iterator.hasNext());
    }

    @ParameterizedTest
    @EnumSource(SchemaType.class)
    public void shouldReverseIterateBothStoreAndCache(final SchemaType schemaType) {
        setUp(schemaType);
        final MergedSortedCacheWindowStoreKeyValueIterator iterator = createIterator(storeKvs, cacheKvs, false);
        assertThat(convertKeyValuePair(iterator.next()), equalTo(KeyValue.pair(new Windowed<>(cacheKey, cacheWindow), cacheKey)));
        assertThat(convertKeyValuePair(iterator.next()), equalTo(KeyValue.pair(new Windowed<>(storeKey, storeWindow), storeKey)));
        assertFalse(iterator.hasNext());
    }

    private KeyValue<Windowed<String>, String> convertKeyValuePair(final KeyValue<Windowed<Bytes>, byte[]> next) {
        final String value = deserializer.deserialize("", next.value);
        return KeyValue.pair(convertWindowedKey(next.key), value);
    }

    private Windowed<String> convertWindowedKey(final Windowed<Bytes> bytesWindowed) {
        final String key = deserializer.deserialize("", bytesWindowed.key().get());
        return new Windowed<>(key, bytesWindowed.window());
    }


    private MergedSortedCacheWindowStoreKeyValueIterator createIterator(final Iterator<KeyValue<Windowed<Bytes>, byte[]>> storeKvs,
                                                                        final Iterator<KeyValue<Bytes, LRUCacheEntry>> cacheKvs,
                                                                        final boolean forward) {
        final DelegatingPeekingKeyValueIterator<Windowed<Bytes>, byte[]> storeIterator =
            new DelegatingPeekingKeyValueIterator<>("store", new KeyValueIteratorStub<>(storeKvs));

        final PeekingKeyValueIterator<Bytes, LRUCacheEntry> cacheIterator =
            new DelegatingPeekingKeyValueIterator<>("cache", new KeyValueIteratorStub<>(cacheKvs));
        return new MergedSortedCacheWindowStoreKeyValueIterator(
            cacheIterator,
            storeIterator,
            new StateSerdes<>("name", Serdes.Bytes(), Serdes.ByteArray()),
            WINDOW_SIZE,
            SINGLE_SEGMENT_CACHE_FUNCTION,
            forward,
            storeKeyToWindowKey,
            windowKeyToBytes
        );
    }
}
