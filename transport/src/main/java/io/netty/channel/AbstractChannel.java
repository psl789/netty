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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    private final Channel parent;
    private final ChannelId id;
    private final Unsafe unsafe;
    private final DefaultChannelPipeline pipeline;
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    private final CloseFuture closeFuture = new CloseFuture(this);

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    private volatile EventLoop eventLoop;
    private volatile boolean registered;
    private boolean closeInitiated;
    private Throwable initialCloseCause;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    /**
     * 总结：完善信息,记录父通道，创建id，unsafe对象，pipleline。
     * 当类型是NioSocketChannel时，
     * 1：记录parent
     * 2：创建id
     * 3：创建Unsafe对象（当类型是是NioSocketChannel）
     * 4：创建pipeline
     * @param parent
     */
    protected AbstractChannel(Channel parent) {
        this.parent = parent;
        id = newId();

        //当类型是：NioSocketChannel,它的Unsafe实例是谁？ NioByteUnsafe
        unsafe = newUnsafe();
        //创建出来当前Channel内部的Pipeline管道
        //创建出来的这个Pipeline内部有两个默认的处理器，分别是HeadContext 和TailContext
        // head<--->tail
        pipeline = newChannelPipeline();
    }

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    protected final int maxMessagesPerWrite() {
        ChannelConfig config = config();
        if (config instanceof DefaultChannelConfig) {
            return ((DefaultChannelConfig) config).getMaxMessagesPerWrite();
        }
        Integer value = config.getOption(ChannelOption.MAX_MESSAGES_PER_WRITE);
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        return value;
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel)} constructor.
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link DefaultChannelPipeline} instance.
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {
        //每个Channel 都有一个属于它自己的 unsafe，每个unsafe都有一个属于它自己的 outboundBuffer。
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        private boolean inFlush0;
        /** true if the channel has never been registered, false otherwise */
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        /**
         * 总结：
         * 1：channel绑定EventLoop
         * 2：向提交EventLoop提交register0异步任务
         *
         *
         * 被执行：异步线程
         * @param eventLoop
         * @param promise
         */
        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            ObjectUtil.checkNotNull(eventLoop, "eventLoop");
            //防止channel重复注册...
            if (isRegistered()) {
                //1. 设置Promise结果为失败..
                //2. 回调监听者，执行失败的逻辑..
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }
            // AbstractChannel.this 获取到 Channel作用域，这个Channel就是unsafe的外层对象。
            // AbstractChannel.this => NioServerSocketChannel对象..
            // AbstractChannel.this.eventLoop = eventLoop->绑定关系.. 后续Channel上的 事件/任务 都会依赖当前EventLoop线程去处理。
            AbstractChannel.this.eventLoop = eventLoop;
            /**
             * eventLoop.inEventLoop() -> 判断当前线程 是不是 当前eventloop 自己 这个线程...
             * 是-> 直接执行（比较特殊，因为我们 channel 支持 unregistor ...）
             * 不是->将注册任务带着promise对象参数【regist0(promise)】提交到eventLoop的工作队列中
             * 这样做目的：为了线程安全，单个线程去执行，不存在并发性。
            */
            if (eventLoop.inEventLoop()) {
                register0(promise);
            } else {
                try {
                    //将注册任务【regist0(promise)】带着promise对象参数，提交到eventLoop的工作队列中，
                    /**eventLoop.execute
                     * ↓ addTask(task);
                     * ↓ !inEventLoop
                     * startThread();
                     * ↓ state == ST_NOT_STARTED && state(ST_NOT_STARTED)->state(ST_STARTED)
                     * doStartThread();
                     * ↓ 交给NioEventLoopGroup中的线程工厂创建线程去执行
                     * threadFactory.newThread(command).start();
                    */
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }
        //目的：
            // 1：完成channel在selector上的0事件注册，
            // 2：拆解CI，告知pipeline，ch已经注册成功了，移除CI这个ctx
            // 3：修改channel在selector为构造channel时的事件
        //这个方法 一定是 当前Channel关联的EventLoop线程执行的
        //参数：promise，表示注册结果，外部可以向它注册监听着....来完成注册后的逻辑....
        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                boolean firstRegistration = neverRegistered;
                //注册..（channel注册到jdk的selector，感兴趣事件0，附加参数NioServerSocketChannel/NioSocketChannel）
                doRegister();

                neverRegistered = false;
                //表示当前Channel已经注册到 多路复用器了..
                registered = true;

                // Ensure we call handlerAdded(...) before we actually notify the promise. This is needed as the
                // user may already fire events through the pipeline in the ChannelFutureListener.
                /**
                 * initRegister中的init的时候由于已经addLast(CI....)
                 * pendingHandlerCallbackHead = Task[ctx[CI]]->Task[ctx[CI]]->Task[ctx[CI]]
                 * 拆解CI       _________________
                 *     pipeline|head<=>CI<=>tail|
                 *             ˉˉˉˉˉˉˉˉˉˉˉˉˉˉˉˉˉˉ
                 *        流程 pipeline.invokeHandlerAddedIfNeeded();
                 *             ↓ firstRegistration=true
                 *             callHandlerAddedForAllHandlers();
                 *             ↓
                 *             PendingHandlerCallback task = pendingHandlerCallbackHead;
                 *             while (task != null) {
                 *                task.execute();
                 *                task = task.next;
                 *             }
                 *             ↓task.execute()= （PendingHandlerAddedTask实例）PendingHandlerAddedTask.execute()
                 *             if (executor.inEventLoop()) {
                 *                callHandlerAdded0(ctx);
                 *             }
                 *             ↓由于本线程是NioEventLoop执行的执行
                 *             callHandlerAdded0(ctx);
                 *             ↓
                 *             ctx.callHandlerAdded();
                 *             ↓
                 *             handler().handlerAdded(this);
                 *             ↓ handler()=CI（ChannelInitializer类不是真正的handler由ChannelHandlerAdapter适配器适配）
                 *             initChannel(ctx)，这里其实就是执行的是Init中的方法，将handler添加到pipeline中，提交异步任务2
                                     *             p.addLast(new ChannelInitializer<Channel>() {
                                     *             @Override
                                     *             public void initChannel(final Channel ch) {
                                     *                 final ChannelPipeline pipeline = ch.pipeline();
                                     *                 //.handler(new LoggingHandler(LogLevel.INFO))
                                     *                 ChannelHandler handler = config.handler();
                                     *                 if (handler != null) {
                                     *                     pipeline.addLast(handler);
                                     *                 }
                                     *
                                     *                 ch.eventLoop().execute(new Runnable() {
                                     *                     @Override
                                     *                     public void run() {
                                     *                         pipeline.addLast(new ServerBootstrapAcceptor(
                                     *                                 ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                                     *                     }
                                     *                 });
                                     *             }
                                     *         });
                 *             ↓最终移除该handler
                 *             removeState(ctx);
                 *
                 * */
                //拆解CI，这步提交了异步任务二（pipeline中添加与连接相关的handler）
                //todo 为什么说ChannelInitializer不是真正的handler
                pipeline.invokeHandlerAddedIfNeeded();
                //这一步会去回调 注册在promise上相关的那些Listener，比如"主线程"在regFuture上注册的监听者。
                /**AbstractBootstrap->doBind()->regFuture.addListener(new ChannelFutureListener() {
                                                //                @Override
                                                //                public void operationComplete(ChannelFuture future) throws Exception {
                                                //                    Throwable cause = future.cause();
                                                //                    if (cause != null) {
                                                //                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                                                //                        // IllegalStateException once we try to access the EventLoop of the Channel.
                                                //                        promise.setFailure(cause);
                                                //                    } else {
                                                //                        // Registration was successful, so set the correct executor to use.
                                                //                        // See https://github.com/netty/netty/issues/2586
                                                //                        promise.registered();
                                                //
                                                //                        doBind0(regFuture, channel, localAddress, promise);
                                                //                    }
                                                //                }
                                                //            });
                                                ↓
                                                doBind0()
                                                ↓ 提交异步任务3
                                                channel.eventLoop().execute(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        if (regFuture.isSuccess()) {
                                                            channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                                                        } else {
                                                            promise.setFailure(regFuture.cause());
                                                        }
                                                    }
                                                });
                                                */
                /**这里提交了异步任务三（做物理上的绑定端口，是out类型的操作，从tail最终到达head，中间的handler对bind不感兴趣）
                 *  ↓
                 *  AbstractUnsafe.bind()
                 *  ↓
                 *  完成端口绑定，向nioEvnetLoop里面提交异步任务4（向pipeline传递激活事件）
                 *  ↓
                 *  异步任务4
                 *        invokeLater(new Runnable() {
                 *            @Override
                 *            public void run() {
                 *                pipeline.fireChannelActive();
                 *            }
                 *        });
                 *  ↓
                 *  pipeline.fireChannelActive();是in类型的事件从head——>tail
                 *   其实只有HeadContext实现了fireChannelActive()
                 *      public void channelActive(ChannelHandlerContext ctx) {
                 *             ctx.fireChannelActive();
                 *             readIfIsAutoRead();
                 *      }
                 *  ↓
                 *  readIfIsAutoRead();
                 *      if (channel.config().isAutoRead()) {
                 *          channel.read();
                 *      }
                 *  ↓read是out操作从tail->head
                 *  HeadContext.read()
                 *  ↓
                 *  unsafe.beginRead()（unsafe==AbstractUnsafe）
                 *  ↓
                 *  调用beginRead()其实就是->NioMessageUnSafe.beginRead()（
                 *  ↓ if ((interestOps & readInterestOp) == 0) {
                 *  ↓   interestOp和readInterestOp是在initAndRegister中的channel = channelFactory.newChannel();这一步创建赋值的
                 *          public NioServerSocketChannel(ServerSocketChannel channel) {
                 *              super(null, channel, SelectionKey.OP_ACCEPT);
                 *              config = new NioServerSocketChannelConfig(this, javaChannel().socket());
                 *          }
                 *  ↓
                 *  selectionKey.interestOps(interestOps | readInterestOp);
                 *  修改channel在selector上注册的感兴趣事件为accept.
                 */
                safeSetSuccess(promise);

                // 向当前Channel的Pipeline 发起 注册完成事件，关注的Handler可以做一些事情。
                pipeline.fireChannelRegistered();
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.


                //channel->NioserverSocketChannel
                //咱们在这一步的时候 完成绑定了吗？ 绑定操作 一定是当前EventLoop线程去做的，当前EventLoop线程在干嘛呢？在执行register0
                //这一步的时候，绑定一定是没完成的！
                //isActive()->false 表示不成立
                if (isActive()) {
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    } else if (config().isAutoRead()) {
                        // This channel was registered before and autoRead() is set. This means we need to begin read
                        // again so that we process inbound data.
                        //
                        // See https://github.com/netty/netty/issues/4805
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }
        //bind方法是有由nioEventLoop去执行的
        //总结：完成端口绑定，向nioEvnetLoop里面提交异步任务4（向pipeline传递激活事件）
        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }
            //什么情况下是isActive()呢？当绑定完成之后才是isActive
            //在这一步还未完成绑定是false
            boolean wasActive = isActive();
            try {
                /** 获取jdk层面的ServerSocketChannel，并且使用它完成真正的绑定工作
                 *    ↓
                 *  javaChannel().bind(localAddress, config.getBacklog());
                 */
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }
            //条件一 !wasActive->true
            //条件二 isActive()->true（因为上面已经完成绑定了）
            if (!wasActive && isActive()) {
                //这里再次向 当前channel#NioEventLoop 工作队列提交 异步任务4
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        //headContext 会响应active事件，如何处理的呢？
                        //再次向当前的Channel的pipeline发起read事件。
                        //read事件，就会修改channel在selector上注册的感兴趣事件为accept.
                        pipeline.fireChannelActive();
                    }
                });
            }
            // promise 这个表示的是绑定结果，谁在等着绑定结果呢？
            // 咱们的启动线程，在该promise上执行的wait操作，所以这一步 绑定完成之后，会将主线程唤醒。
            safeSetSuccess(promise);
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
                // Reset remoteAddress and localAddress
                remoteAddress = null;
                localAddress = null;
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public void close(final ChannelPromise promise) {
            assertEventLoop();

            ClosedChannelException closedChannelException =
                    StacklessClosedChannelException.newInstance(AbstractChannel.class, "close(ChannelPromise)");
            close(promise, closedChannelException, closedChannelException, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the shutdown.
                            doShutdownOutput();
                            promise.setSuccess();
                        } catch (Throwable err) {
                            promise.setFailure(err);
                        } finally {
                            // Dispatch to the EventLoop
                            eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {
                                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Execute the shutdown.
                    doShutdownOutput();
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }

        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            buffer.failFlushed(cause, false);
            buffer.close(cause, true);
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }

        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (closeInitiated) {
                if (closeFuture.isDone()) {
                    // Closed already.
                    safeSetSuccess(promise);
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            closeInitiated = true;

            final boolean wasActive = isActive();
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            doClose0(promise);
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        // Fail all the queued messages
                                        outboundBuffer.failFlushed(cause, notify);
                                        outboundBuffer.close(closeCause);
                                    }
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify);
                        outboundBuffer.close(closeCause);
                    }
                }
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        private void doClose0(ChannelPromise promise) {
            try {
                doClose();
                closeFuture.setClosed();
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        @Override
        public final void closeForcibly() {
            assertEventLoop();

            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        if (fireChannelInactive) {
                            pipeline.fireChannelInactive();
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        if (registered) {
                            registered = false;
                            pipeline.fireChannelUnregistered();
                        }
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        @Override
        public final void beginRead() {
            assertEventLoop();

            try {
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        /**
         * @param msg    一般都是ByteBuf对象，当然有其他情况 比如说FileRegion... 不考虑这种情况..
         * @param promise 业务如果关注 本次 写操作是否成功 或者失败，可以手动提交一个跟msg相关的promise，promise 内可以注册一些监听者，用于处理结果。
         *    总结：
         *        1：将 ByteBuf数据 加入 到 出站缓冲区内。并赋值unflushedEntry和tailEntry
         */
        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                try {
                    // release message now to prevent resource-leak
                    ReferenceCountUtil.release(msg);
                } finally {
                    // If the outboundBuffer is null we know the channel was closed and so
                    // need to fail the future right away. If it is not null the handling of the rest
                    // will be done in flush0()
                    // See https://github.com/netty/netty/issues/2362
                    safeSetFailure(promise,
                            newClosedChannelException(initialCloseCause, "write(Object, ChannelPromise)"));
                }
                return;
            }
            //表示 msg 数据大小
            int size;
            try {
                //msg 一般都是ByteBuf对象
                //ByteBuf 类型 是 heap类型的话，这里会将它转换为direct类型。
                msg = filterOutboundMessage(msg);
                // 获取当前消息，有效数据量大小
                size = pipeline.estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                try {
                    ReferenceCountUtil.release(msg);
                } finally {
                    safeSetFailure(promise, t);
                }
                return;
            }
            //将 ByteBuf数据 加入 到 出站缓冲区内。并赋值unflushedEntry和tailEntry
            //参数1：msg，ByteBuf 对象，并且这个ByteBuf管理的内存归属是direct
            //参数2：size，数据量大小
            //参数3：promise，业务如果关注 本次 写操作是否成功 或者失败，可以手动提交一个跟msg相关的promise，promise 内可以注册一些监听者，用于处理结果。
            outboundBuffer.addMessage(msg, size, promise);
        }

        @Override
        public final void flush() {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                return;
            }
            //预准备刷新工作
            //将flushedEntry 指向第一个需要刷新的entry节点
            //计算出flushedEntry----> tailEntry 总共有多少entry需要被刷新，值记录在flushed字段内。
            outboundBuffer.addFlush();
            //真正刷新的工作
            flush0();
        }

        @SuppressWarnings("deprecation")
        protected void flush0() {
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }

            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {
                try {
                    // Check if we need to generate the exception at all.
                    if (!outboundBuffer.isEmpty()) {
                        if (isOpen()) {
                            outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                        } else {
                            // Do not trigger channelWritabilityChanged because the channel is closed already.
                            outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause, "flush0()"), false);
                        }
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                //正常逻辑，执行到这里。
                //参数：当前ch的出站缓冲区
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                handleWriteError(t);
            } finally {
                inFlush0 = false;
            }
        }

        protected final void handleWriteError(Throwable t) {
            if (t instanceof IOException && config().isAutoClose()) {
                /**
                 * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                 * failing all flushed messages and also ensure the actual close of the underlying transport
                 * will happen before the promises are notified.
                 *
                 * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                 * may still return {@code true} even if the channel should be closed as result of the exception.
                 */
                initialCloseCause = t;
                close(voidPromise(), t, newClosedChannelException(t, "flush0()"), false);
            } else {
                try {
                    shutdownOutput(voidPromise(), t);
                } catch (Throwable t2) {
                    initialCloseCause = t;
                    close(voidPromise(), t2, newClosedChannelException(t, "flush0()"), false);
                }
            }
        }

        private ClosedChannelException newClosedChannelException(Throwable cause, String method) {
            ClosedChannelException exception =
                    StacklessClosedChannelException.newInstance(AbstractChannel.AbstractUnsafe.class, method);
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newClosedChannelException(initialCloseCause, "ensureOpen(ChannelPromise)"));
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     *
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }
}
