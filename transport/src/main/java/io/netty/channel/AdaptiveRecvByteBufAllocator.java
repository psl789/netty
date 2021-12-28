/*
 * Copyright 2012 The Netty Project
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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    //索引增量 4
    private static final int INDEX_INCREMENT = 4;
    //索引减量 1
    private static final int INDEX_DECREMENT = 1;
    // size table 取guess值的地方
    private static final int[] SIZE_TABLE;

    /**
     * 初始化 SIZE_TABLE
     * |16|32|48|64|........
     */
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        //向size数组 添加：16，32，48....496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }
        //继续向size数组 添加：512，1024，2048.....一直到int值溢出...成为负数
        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        //数组赋值
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    //2分查找，查找下标。
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;
        //minIndex参数1：64在SIZE_TABLE的下标
        //maxIndex参数2：65536在SIZE_TABLE的下标
        //initial参数3：1024
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            //nextReceiveBufferSize 表示下一次分配出来的byteBuf容量大小。 默认第一次情况下ByteBuf是1024/2048
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            //条件成立：说明读取的数据量 与评估的数据量一致.. 说明ch内可能还有数据未读取完..还需要继续
            if (bytes == attemptedBytesRead()) {
                //这个方法想要更新 nextReceiveBufferSize 大小，因为前面评估的量 被读满了..可能意味着 ch内有很多数据.. 我们需要更大的容器。
                record(bytes);
            }
            //做记录，更新最后一次读的字节数量，累加已经读的消息size总大小
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        //actualReadBytes：真实读取的数据量，本次从ch内读取的数据量
        private void record(int actualReadBytes) {
            //举个例子：
            //假设 SIZE_TABLE[idx] = 512 => SIZE_TABLE[idx-1] = 496
            //如果本次读取的数据量<=496,说明ch的缓冲区数据 不是很多，可能就不需要那么的ByteBuf
            //如果第二次读取的数据量<=496,说明ch的缓冲区数据不是很多，不需要那么大的ByteBuf
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    //初始阶段 定义过：最小 不能 小于 TABLE_SIZE[minIndex]
                    index = max(index - INDEX_DECREMENT, minIndex);
                    //获取相对减小的BufferSize值，赋值给nextReceiveBufferSize
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    //设置成true
                    decreaseNow = true;
                }//条件成立：说明本次 ch 请求，已经将 ByteBuf 容器装满了.. 说明ch内可能还有很多数据..需要装，所以让index右移一位，
                // 获取出来一个更大的nextReceiveBufferSize，构建更大的ByteBuf对象
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }
        //挑出最合适 当前已经读的消息size总大小的ByteBuf容器
        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 参数1：64
        // 参数2：1024
        // 参数3：65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    //目的：使用二分查找找出合法的minIndex和maxIndex值，赋值初始值为1024
    // 参数1：64
    // 参数2：1024
    // 参数3：65536
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }
        //maxIndex 使用二分查找获取mininum size 在 数组内的下标
        int minIndex = getSizeTableIndex(minimum);
        //确保SIZE_TABLE[maxIndex] >maximum
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }
        //maxIndex 使用二分查找获取maxinum size 在 数组内的下标
        //因为不能超出 maxinum值，所以这里左移
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }
        //初始值1024
        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        //minIndex参数1：64在SIZE_TABLE的下标
        //maxIndex参数2：65536在SIZE_TABLE的下标
        //initial参数3：1024
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
