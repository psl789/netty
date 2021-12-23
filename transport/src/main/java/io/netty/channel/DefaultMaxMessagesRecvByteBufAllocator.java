/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private final boolean ignoreBytesRead;
    //每次读循环操作 最大能读取的消息数量，每次ch内拉一次数据 称为一个消息
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        this(maxMessagesPerRead, false);
    }

    DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead, boolean ignoreBytesRead) {
        this.ignoreBytesRead = ignoreBytesRead;
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        // channel#config
        private ChannelConfig config;
        //每次读循环操作 最大能读取的消息数量，每到ch内拉一次数据 称为一个消息
        private int maxMessagePerRead;
        //已经读的消息数量
        private int totalMessages;
        //已经读的消息size总大小
        private int totalBytesRead;
        //预估读的字节数量
        private int attemptedBytesRead;
        //最后一次读的字节数量
        private int lastBytesRead;
        //true
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                // 预估读取量 == 最后一次读取量
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         * 重置当前Handler
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            // 重新设置 读循环操作 最大可读取消息量，默认情况下 是16 服务端 和客户端 都是16
            maxMessagePerRead = maxMessagesPerRead();
            //统计字段 归0
            totalMessages = totalBytesRead = 0;
        }
        //参数：alloc，是真正的大佬，真正分配内存的缓冲区分配器
        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            //guess() 根据读循环过程中 的上下文 评估一个适合本次 读大小的一个预估值
            //alloc.ioBuffer 真正的分配 缓冲区 对象。
            return alloc.ioBuffer(guess());
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            // continueReading 控制着 读循环 是否继续循环，非常重要！
            //什么情况下才会循环？4个条件全部成立 都是true!
            //1. config.isAutoRead() 默认都是true
            //2.  maybeMoreDataSupplier.get()-> true 代表最后一次读取的数据量 和评估数据量一致，说明ch内可能还剩余数据 未读取完，还需要继续
                                            //  false： 代表不循环了
                                             //        1. 评估的数据量 产生的ByteBuf > 剩余数据量
                                            //         2. ch close,lastBytesRead 会是 -1。
            //3. totalMessages < maxMessagePerRead: 一次unsafe.read 最多能从ch读取16次数据，不能超过16
            //4. totalBytesRead > 0
                /**4.1: 客户端  A：正常情况下都是true，totalBytesRead>0,
                               B：什么时候不会大于0？读取数据量太多了，超过了intMax值了..会导致totalBytesRead<0
                                  也就是说客户端一次网络传输最多能传intMax值大小的byte
                 */
                //4.2：服务端 这里的值会是 0>0 => false，服务端每次unsafe.read() 只进行一次循环

            return config.isAutoRead() &&

                   (!respectMaybeMoreData || maybeMoreDataSupplier.get())
                    && totalMessages < maxMessagePerRead
                    && (ignoreBytesRead || totalBytesRead > 0);
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
