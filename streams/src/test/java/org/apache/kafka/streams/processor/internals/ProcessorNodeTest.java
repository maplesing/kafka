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
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.test.InternalMockProcessorContext;
import org.apache.kafka.test.StreamsTestUtils;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl.ROLLUP_VALUE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProcessorNodeTest {

    @Test
    public void shouldThrowStreamsExceptionIfExceptionCaughtDuringInit() {
        final ProcessorNode<Object, Object, Object, Object> node =
            new ProcessorNode<>("name", new ExceptionalProcessor(), Collections.emptySet());
        assertThrows(StreamsException.class, () -> node.init(null));
    }

    @Test
    public void shouldThrowStreamsExceptionIfExceptionCaughtDuringClose() {
        final ProcessorNode<Object, Object, Object, Object> node =
            new ProcessorNode<>("name", new ExceptionalProcessor(), Collections.emptySet());
        assertThrows(StreamsException.class, () -> node.init(null));
    }

    private static class ExceptionalProcessor implements Processor<Object, Object, Object, Object> {
        @Override
        public void init(final ProcessorContext<Object, Object> context) {
            throw new RuntimeException();
        }

        @Override
        public void process(final Record<Object, Object> record) {
            throw new RuntimeException();
        }

        @Override
        public void close() {
            throw new RuntimeException();
        }
    }

    private static class NoOpProcessor implements Processor<Object, Object, Object, Object> {
        @Override
        public void process(final Record<Object, Object> record) {
        }
    }

    @Test
    public void testMetricsWithBuiltInMetricsVersionLatest() {
        final Metrics metrics = new Metrics();
        final StreamsMetricsImpl streamsMetrics =
            new StreamsMetricsImpl(metrics, "test-client", StreamsConfig.METRICS_LATEST, new MockTime());
        final InternalMockProcessorContext<Object, Object> context = new InternalMockProcessorContext<>(streamsMetrics);
        final ProcessorNode<Object, Object, Object, Object> node =
            new ProcessorNode<>("name", new NoOpProcessor(), Collections.emptySet());
        node.init(context);

        final String threadId = Thread.currentThread().getName();
        final String[] latencyOperations = {"process", "punctuate", "create", "destroy"};
        final String groupName = "stream-processor-node-metrics";
        final Map<String, String> metricTags = new LinkedHashMap<>();
        final String threadIdTagKey = "client-id";
        metricTags.put("processor-node-id", node.name());
        metricTags.put("task-id", context.taskId().toString());
        metricTags.put(threadIdTagKey, threadId);

        for (final String opName : latencyOperations) {
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-latency-avg", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-latency-max", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-rate", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-total", groupName, metricTags));
        }

        // test parent sensors
        metricTags.put("processor-node-id", ROLLUP_VALUE);
        for (final String opName : latencyOperations) {
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-latency-avg", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-latency-max", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-rate", groupName, metricTags));
            assertFalse(StreamsTestUtils.containsMetric(metrics, opName + "-total", groupName, metricTags));
        }
    }

    @Test
    public void testTopologyLevelClassCastException() {
        // Serdes configuration is missing and no default is set which will trigger an exception
        final StreamsBuilder builder = new StreamsBuilder();

        builder.<String, String>stream("streams-plaintext-input")
            .flatMapValues(value -> Collections.singletonList(""));
        final Topology topology = builder.build();
        final Properties config = new Properties();
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);

        try (final TopologyTestDriver testDriver = new TopologyTestDriver(topology, config)) {
            final TestInputTopic<String, String> topic = testDriver.createInputTopic("streams-plaintext-input", new StringSerializer(), new StringSerializer());

            final StreamsException se = assertThrows(StreamsException.class, () -> topic.pipeInput("a-key", "a value"));
            final String msg = se.getMessage();
            assertTrue(msg.contains("ClassCastException"), "Error about class cast with serdes");
            assertTrue(msg.contains("Serdes"), "Error about class cast with serdes");
        }
    }

    @Test
    public void testTopologyLevelConfigException() {
        // Serdes configuration is missing and no default is set which will trigger an exception
        final StreamsBuilder builder = new StreamsBuilder();

        builder.<String, String>stream("streams-plaintext-input")
            .flatMapValues(value -> Collections.singletonList(""));
        final Topology topology = builder.build();

        final StreamsException se = assertThrows(StreamsException.class, () -> new TopologyTestDriver(topology));
        assertThat(se.getMessage(), containsString("Failed to initialize key serdes for source node"));
        assertThat(se.getCause().getMessage(), containsString("Please specify a key serde or set one through StreamsConfig#DEFAULT_KEY_SERDE_CLASS_CONFIG"));
    }

    private static class ClassCastProcessor extends ExceptionalProcessor {

        @Override
        public void init(final ProcessorContext<Object, Object> context) {
        }

        @Override
        public void process(final Record<Object, Object> record) {
            throw new ClassCastException("Incompatible types simulation exception.");
        }
    }

    @Test
    public void testTopologyLevelClassCastExceptionDirect() {
        final Metrics metrics = new Metrics();
        final StreamsMetricsImpl streamsMetrics =
            new StreamsMetricsImpl(metrics, "test-client", StreamsConfig.METRICS_LATEST, new MockTime());
        final InternalMockProcessorContext<Object, Object> context = new InternalMockProcessorContext<>(streamsMetrics);
        final ProcessorNode<Object, Object, Object, Object> node =
            new ProcessorNode<>("pname", new ClassCastProcessor(), Collections.emptySet());
        node.init(context);
        final StreamsException se = assertThrows(
            StreamsException.class,
            () -> node.process(new Record<>("aKey", "aValue", 0))
        );
        assertThat(se.getCause(), instanceOf(ClassCastException.class));
        assertThat(se.getMessage(), containsString("default Serdes"));
        assertThat(se.getMessage(), containsString("input types"));
        assertThat(se.getMessage(), containsString("pname"));
    }
}
