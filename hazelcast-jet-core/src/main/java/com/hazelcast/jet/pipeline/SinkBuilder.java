/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.pipeline;

import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.Processor.Context;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.function.BiConsumerEx;
import com.hazelcast.jet.function.ConsumerEx;
import com.hazelcast.jet.function.FunctionEx;
import com.hazelcast.jet.function.SupplierEx;
import com.hazelcast.jet.impl.pipeline.SinkImpl;
import com.hazelcast.util.Preconditions;

import javax.annotation.Nonnull;

import static com.hazelcast.jet.core.processor.SinkProcessors.writeBufferedP;
import static com.hazelcast.jet.impl.util.Util.checkSerializable;

/**
 * See {@link SinkBuilder#sinkBuilder(String, FunctionEx)}.
 *
 * @param <W> type of the writer object
 * @param <T> type of the items the sink will accept
 *
 * @since 3.0
 */
public final class SinkBuilder<W, T> {

    private final FunctionEx<? super Context, ? extends W> createFn;
    private final String name;
    private BiConsumerEx<? super W, ? super T> receiveFn;
    private ConsumerEx<? super W> flushFn = ConsumerEx.noop();
    private ConsumerEx<? super W> destroyFn = ConsumerEx.noop();
    private int preferredLocalParallelism = 1;

    private SinkBuilder(
            @Nonnull String name,
            @Nonnull FunctionEx<? super Context, ? extends W> createFn
    ) {
        checkSerializable(createFn, "createFn");
        this.name = name;
        this.createFn = createFn;
    }

    /**
     * Returns a builder object that offers a step-by-step fluent API to build
     * a custom {@link Sink} for the Pipeline API. It allows you to keep a
     * single-threaded, stateful writer object in each instance of a Jet worker
     * dedicated to driving the sink. Its primary intended purpose is to serve
     * as the holder of references to external resources and optional buffers.
     * Keep in mind that only the writer object may be stateful; the functions
     * you provide must hold no mutable state of their own.
     * <p>
     * These are the callback functions you can provide to implement the sink's
     * behavior:
     * <ol><li>
     *     {@code createFn} creates the writer. Gets the processor context as
     *     argument which can be used to obtain local Jet instance, global
     *     processor index etc. It will be called once for each worker thread.
     *     This component is required.
     * </li><li>
     *     {@code onReceiveFn} gets notified of each item the sink receives and
     *     (typically) passes it to the writer. This component is required.
     * </li><li>
     *     {@code flushFn} flushes the writer. This component is optional.
     * </li><li>
     *     {@code destroyFn} destroys the writer. This component is optional.
     * </li></ol>
     * The returned sink will be non-cooperative and will have preferred local
     * parallelism of 1. It doesn't participate in the fault-tolerance protocol,
     * which means you can't remember across a job restart which items you
     * already received. The sink will still receive each item at least once,
     * thus complying with the <em>at-least-once</em> processing guarantee. If
     * the sink is idempotent (suppresses duplicate items), it will also be
     * compatible with the <em>exactly-once</em> guarantee.
     *
     * @param <W> type of the writer object
     *
     * @since 3.0
     */
    @Nonnull
    public static <W> SinkBuilder<W, Void> sinkBuilder(
            @Nonnull String name,
            @Nonnull FunctionEx<Context, ? extends W> createFn
    ) {
        return new SinkBuilder<>(name, createFn);
    }

    /**
     * Sets the function Jet will call upon receiving an item. The function
     * receives two arguments: the writer object (as provided by the {@link
     * #createFn} and the received item. Its job is to push the item to the
     * writer.
     *
     * @param receiveFn the "add item to the writer" function
     * @param <T_NEW> type of the items the sink will accept
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T_NEW> SinkBuilder<W, T_NEW> receiveFn(
            @Nonnull BiConsumerEx<? super W, ? super T_NEW> receiveFn
    ) {
        checkSerializable(receiveFn, "receiveFn");
        SinkBuilder<W, T_NEW> newThis = (SinkBuilder<W, T_NEW>) this;
        newThis.receiveFn = receiveFn;
        return newThis;
    }

    /**
     * Sets the function that implements the sink's flushing behavior. If your
     * writer is buffered, instead of relying on some automatic flushing policy
     * you can provide this function so Jet can choose the best moment to
     * flush.
     * <p>
     * You are not required to provide this function in case your implementation
     * doesn't need it.
     *
     * @param flushFn the optional "flush the writer" function
     */
    @Nonnull
    public SinkBuilder<W, T> flushFn(@Nonnull ConsumerEx<? super W> flushFn) {
        checkSerializable(flushFn, "flushFn");
        this.flushFn = flushFn;
        return this;
    }

    /**
     * Sets the function that will destroy the writer and perform any cleanup. The
     * function is called when the job has been completed or cancelled. Jet guarantees
     * that no new items will be received in between the last call to {@code flushFn}
     * and the call to {@code destroyFn}.
     * <p>
     * You are not required to provide this function in case your implementation
     * doesn't need it.
     *
     * @param destroyFn the optional "destroy the writer" function
     */
    @Nonnull
    public SinkBuilder<W, T> destroyFn(@Nonnull ConsumerEx<? super W> destroyFn) {
        checkSerializable(destroyFn, "destroyFn");
        this.destroyFn = destroyFn;
        return this;
    }

    /**
     * Sets the local parallelism of the sink. On each member of the cluster
     * Jet will create this many parallel processors for the sink. To identify
     * each processor instance, your {@code createFn} can consult {@link
     * Processor.Context#totalParallelism() procContext.totalParallelism()} and {@link
     * Processor.Context#globalProcessorIndex() procContext.globalProcessorIndex()}.
     * Jet calls {@code createFn} exactly once with each {@code
     * globalProcessorIndex} from 0 to {@code totalParallelism - 1}.
     * <p>
     * The default value of this property is 1.
     */
    @Nonnull
    public SinkBuilder<W, T> preferredLocalParallelism(int preferredLocalParallelism) {
        Vertex.checkLocalParallelism(preferredLocalParallelism);
        this.preferredLocalParallelism = preferredLocalParallelism;
        return this;
    }

    /**
     * Creates and returns the {@link Sink} with the components you supplied to
     * this builder.
     */
    @Nonnull
    public Sink<T> build() {
        Preconditions.checkNotNull(receiveFn, "receiveFn must be set");
        SupplierEx<Processor> supplier = writeBufferedP(createFn, receiveFn, flushFn, destroyFn);
        return new SinkImpl<>(name, ProcessorMetaSupplier.of(supplier, preferredLocalParallelism));
    }
}
