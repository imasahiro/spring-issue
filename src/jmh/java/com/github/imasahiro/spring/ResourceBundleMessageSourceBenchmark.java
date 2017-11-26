/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.github.imasahiro.spring;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.context.MessageSource;
import org.springframework.context.support.AbstractResourceBasedMessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Compare caching message source {@link ConcurrentHashMap} to using {@link HashMap} with {@code synchronized}.
 * {@link HashMap} with synchronization is obviously slower.
 * Env:
 * <ul>
 *     <li>CPU: Intel(R) Xeon(R) CPU E5-2630 v4 @ 2.20GHz, 4core
 *     <li>MEM: 8GB
 *     <li>OS : CentOS 7.3.1611, kernel=3.10.0-514.21.2.el7.x86_64
 *     <li>JVM: JDK 1.8.0_121, VM 25.121-b13
 * </ul>
 * <pre>
 * # Thread=1
 * Benchmark                                         Mode  Cnt        Score       Error  Units
 * ResourceBundleMessageSourceBenchmark.concurrent  thrpt   20  2460083.135 ± 21356.649  ops/s
 * ResourceBundleMessageSourceBenchmark.original    thrpt   20  2628372.407 ± 31603.771  ops/s
 *
 * # Thread=20
 * Benchmark                                         Mode  Cnt         Score        Error  Units
 * ResourceBundleMessageSourceBenchmark.concurrent  thrpt   20  11137855.564 ± 344640.055  ops/s
 * ResourceBundleMessageSourceBenchmark.original    thrpt   20   1924866.006 ± 332692.104  ops/s
 * </pre>
 */
public class ResourceBundleMessageSourceBenchmark {
    private static final MessageSource defaultMessageSource =
            setupMessageSource(new ResourceBundleMessageSource());
    private static final MessageSource concurrentMessageSource =
            setupMessageSource(new ConcurrentResourceBundleMessageSource());

    private static MessageSource setupMessageSource(AbstractResourceBasedMessageSource messageSource) {
        messageSource.setBasenames("messages/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.toString());
        messageSource.setFallbackToSystemLocale(true);
        messageSource.setAlwaysUseMessageFormat(false);
        messageSource.setCacheSeconds(-1);
        return messageSource;
    }

    private static String getMessage(MessageSource messageSource, String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.ENGLISH);
    }

    private static void run(Blackhole bh, MessageSource messageSource) {
        requireNonNull(getMessage(messageSource, "btn.1"));
        bh.consume(getMessage(messageSource, "btn.2"));
        bh.consume(getMessage(messageSource, "btn.3"));
        bh.consume(getMessage(messageSource, "btn.4"));
        bh.consume(getMessage(messageSource, "btn.5"));
        bh.consume(getMessage(messageSource, "btn.6"));
    }

    @Benchmark
    public void original(Blackhole bh) {
        run(bh, defaultMessageSource);
    }

    @Benchmark
    public void concurrent(Blackhole bh) {
        run(bh, concurrentMessageSource);
    }
}
