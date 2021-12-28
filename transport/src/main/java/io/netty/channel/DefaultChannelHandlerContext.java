/*
* Copyright 2014 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;

final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {

    private final ChannelHandler handler;

    /**
     *
     * @param pipeline 外层容器，装 CTX【Handler】 的管道容器
     * @param executor  事件执行器，一般情况下 这里是null,除非你执定
     * @param name      用户定义的Handler名称name
     * @param handler   业务真正实现的处理器
     */
    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, EventExecutor executor, String name, ChannelHandler handler) {
        /**
         * pipeline 外层容器，装 CTX【Handler】 的管道容器
         * executor  事件执行器，一般情况下 这里是null,除非你执定
         * name      用户定义的Handler名称name
         * handler.getClass() 处理器真实的class
         */
        super(pipeline, executor, name, handler.getClass());
        this.handler = handler;
    }

    @Override
    public ChannelHandler handler() {
        return handler;
    }
}
