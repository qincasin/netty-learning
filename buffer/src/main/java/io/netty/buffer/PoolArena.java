/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;

    private final int maxOrder;
    final int pageSize;
    final int pageShifts;
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    private final PoolSubpage<T>[] tinySubpagePools;
    private final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    // 参数1：allocator 对象
    // 参数2：pageSize,8k
    // 参数3：maxOrder，11
    // 参数4：pageShifts,13 ，1 << 13 => pageSize
    // 参数5：chunkSize,16mb
    // 参数6：directMemoryCacheAlignment 0
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        // 记录当前 arena 的爸爸 是谁，arena归属的 alloctor 对象。
        this.parent = parent;
        // 8k
        this.pageSize = pageSize;
        // 11
        this.maxOrder = maxOrder;
        // 13
        this.pageShifts = pageShifts;
        // 16mb
        this.chunkSize = chunkSize;
        // 0
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;

        // 8k - 1 => 0b 1111111111111
        // ~(0b 1111111111111)
        //  (0b 1111 1111 1111 1111 1110 0000 0000 0000)
        // 作用：外部申请内存 大小如果大于 1 page， 去和 subpageOverflowMask 进行位于运算，会得到一个 != 0 的值。
        subpageOverflowMask = ~(pageSize - 1);

        // 因为tiny有32种小规格：16b,32b,48b...496b  ，所以这里创建一个长度为 32 的 PoolSubpage 数组类型 去引用 tiny 类型的 Subpage ，
        // 供arena 区域共享使用。
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);

        // 赋值。每个数组内的元素 都是 PoolSubpage 对象，该对象为 head，head 在初始时 prev next 都指向自身。
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // 13 - 9 => 4
        numSmallSubpagePools = pageShifts - 9;
        // 因为small有4种小规格:512b,1024b,2048b,4096b
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);

        // 赋值。每个数组内的元素 都是 PoolSubpage 对象，该对象为 head，head 在初始时 prev next 都指向自身。
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }


        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    // 参数1： cache 当前线程相关的PoolThreadCache对象
    // 参数2：reqCapacity，业务层需要的内存容量
    // 参数3：maxCapacity 最大内存大小
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        // 获取一个ByteBuf对象，在这一步时 该ByteBuf 还未管理任何内存。 它作为 内存 容器...
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);

        // 参数1：cache 当前线程相关的PoolThreadCache对象
        // 参数2：buf，返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
        // 参数3：reqCapacity，业务层需要的内存容量
        allocate(cache, buf, reqCapacity);

        return buf;
    }

    static int tinyIdx(int normCapacity) {
        // 48 => 0b 110000
        // 0b 110000  >>> 4 => 0b 11 => 3
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        // 2048 => 0b 1000 0000 0000
        // 0b 10 => 2
        int i = normCapacity >>> 10;

        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        // 0xFFFFFE00 => 0b 1111111111111111111 11110 0000 0000    是一个掩码
        // 任何大于512的数字与该值进行 按位与运算， 都会得到一个 非0 值。
        return (normCapacity & 0xFFFFFE00) == 0;
    }


    // 参数1：cache 当前线程相关的PoolThreadCache对象
    // 参数2：buf，返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
    // 参数3：reqCapacity，业务层需要的内存容量
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {


        // 将用户的的req转换为 netty 内存池 的规格size
        final int normCapacity = normalizeCapacity(reqCapacity);

        // CASE1：说明 转换出的 规格Size 是 tiny 或者 small 类型。
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            // arena 内有两个数组 tinySubpagePools 和 smallSubpagePools， 根据 normCapacity 计算出合适的 下标位置。
            int tableIdx;
            // table　最终会指向　tinySubpagePools　或者 smallSubpagePools 其中的一个。
            PoolSubpage<T>[] table;
            // 计算当前规格size 是否为 tiny 类型。
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                // 先不考虑 这里申请内存，假设这一步申请失败，等后面讲完 释放内存的逻辑之后，再看。
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }

                // 假设 normCapacity 是 48 .
                tableIdx = tinyIdx(normCapacity);
                // table指向　tinySubpagePools 数组
                table = tinySubpagePools;
            } else {
                // 执行到这个分支 说明 normCapacity 一定是 small 规格。

                // 先不考虑 这里申请内存，假设这一步申请失败，等后面讲完 释放内存的逻辑之后，再看。
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }

                // 假设 normCapacity 是 2048  会计算出来 2
                tableIdx = smallIdx(normCapacity);
                // table 指向 smallSubpagePools
                table = smallSubpagePools;
            }

            // 上面别管 tiny 还是 small ，总之 tableIdx 和 table 这俩 都已经计算完了..

            // 这一步会拿到一个符合规格的 head 节点。
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            // head 节点作为 锁
            synchronized (head) {

                // 初始化时  创建出来的 head 节点 prev 和 next 是一个指向自己的节点。
                // 只有 arena 内 申请过 某个 规格的 subpage 后，对应的 下标的桶内 才有 page。
                final PoolSubpage<T> s = head.next;

                // 条件成立：说明 该规格的 桶内 有 subpage.  先不考虑这种情况。
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 不讲了...
                    long handle = s.allocate();
                    assert handle >= 0;
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    incTinySmallAllocation(tiny);
                    return;
                }
            }


            synchronized (this) {
                // 最复杂的逻辑.. arena的subpage  和 线程 cache 都没能 满足申请内存的请求，则走 allocateNormal 逻辑。
                // 参数1：buf,返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
                // 参数2：reqCapacity，业务层需要的内存容量
                // 参数3：将req 转换成的规格size。
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            incTinySmallAllocation(tiny);
            return;
        }

        // CASE2：转换出来的 规格size 归属 normal 类型 8k  16k  32k .... <= chunkSize
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        }

        // CASE3： 超大规格，超过 chunkSize。 （用户请求的空间容量超过了 16mb）
        else {
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    // Method must be called inside synchronized(this) { ... } block

    // 参数1：buf,返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
    // 参数2：reqCapacity，业务层需要的内存容量
    // 参数3：将req 转换成的规格size。
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 程序先尝试到 PoolChunkList 内去申请内存... 看源码 暂不考虑 PoolChunkList内部申请的逻辑，看最复杂的情况。
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }
        // 执行到这里，说明在 PCL 没能申请内存成功，需要创建一个新的chunk ，在新chunk内申请内存。


        // Add a new chunk.
        // 参数1：pageSize 8k
        // 参数2：maxOrder 11
        // 参数3：pageShifts 13
        // 参数4：chunkSize 16mb
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);

        // 参数1：buf 返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
        // 参数2：reqCapacity，业务层需要的内存容量
        // 参数3：将req 转换成的规格size。
        boolean success = c.allocate(buf, reqCapacity, normCapacity);

        assert success;
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    // 参数1：chunk byteBuf 管理内存的 归属chunk
    // 参数2：tmpNioBuf 不关心.. 可能是null
    // 参数3：handle  申请内存时  表示 内存 位置信息的 handle
    // 参数4：maxLength  byteBuf 可用内存的最大 大小
    // 参数5：cache 申请byteBuf对象的 线程，释放内存时  优先 将 内存位置信息 缓存到 线程本地。
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 看这里。


            // 获取出 待归还内存的规格
            SizeClass sizeClass = sizeClass(normCapacity);

            // 优先将 待归还 内存位置信息 加入到 线程 poolThreadCache 对象内了。
            // 参数1：this ，当前arena对象
            // 参数2：chunk，当前 byteBuf 占用内存 归属chunk
            // 参数3：nioBuffer ，可能是null.,.
            // 参数4：handle   申请内存时  表示 内存 位置信息的 handle
            // 参数5：normCapacity  规格大小
            // 参数6：sizeClass   tiny small normal
            // cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)

            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                // 缓存成功  从这里返回。
                return;
            }

            // 执行到这里，说明 缓存到 线程本地 失败.. 需要走 正常 释放逻辑。

            // 参数1：当前 byteBuf 占用内存 归属chunk
            // 参数2：handle   申请内存时  表示 内存 位置信息的 handle
            // 参数3：待归还内存的规格  tiny small normal
            // 参数4：nioBuffer  可能是null.,.
            // 参数5：false。。
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);

        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    // 参数1：当前 byteBuf 占用内存 归属chunk
    // 参数2：handle   申请内存时  表示 内存 位置信息的 handle
    // 参数3：待归还内存的规格  tiny small normal
    // 参数4：nioBuffer  可能是null.,.
    // 参数5：false。。
    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }



            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    // 参数1：规格后的elemSize
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        // 指向pools数组下标 符合当前elemSize的桶
        int tableIdx;

        // table 指向 tinySubpagePools 或者 smallSubpagePools 其中一个
        PoolSubpage<T>[] table;

        if (isTiny(elemSize)) { // < 512
            // 计算出 tiny elemSize 对应的数组下标
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;

        } else { // elemSize > 512 并且 elemSize < 1page

            tableIdx = 0;

            // 代入法：比如 elemSize = 1024 、
            // 1024 => 0b 0100 0000 0000
            // 1024 >>>= 10 => 0b 1
            elemSize >>>= 10;

            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
            // 上面算法：
            // 计算出 512b 对应的下标为 0
            // 计算出 1024b 对应的下标为 1
            // 计算出 2048b 对应的下标为 2
            // 计算出 4096b 对应的下标为 3
        }

        // 返回当前桶的 head节点，head 节点创建时 为一个 指向自身的 subpage 对象。
        return table[tableIdx];
    }

    // 参数：reqCapacity 用户指定请求容量大小，是一个随意值
    // return 需要返回一个规格的size
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        // 一般不成立，除非业务申请非常大的 buffer时 才走..
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        // 条件成立：说明reqCapacity 是一个大于 512 的值，规格排除掉  tiny 这个情况。
        if (!isTiny(reqCapacity)) { // >= 512
            // small、normal

            // Doubled
            // 假设 reqCapacity 值为 555
            int normalizedCapacity = reqCapacity;
            // 0b 0000 0000 0000 0000 0000 0010 0010 1010
            normalizedCapacity --;
            // 0b 0000 0000 0000 0000 0000 0010 0010 1010
            // 0b 0000 0000 0000 0000 0000 0001 0001 0101
            // 0b 0000 0000 0000 0000 0000 0011 0011 1111
            normalizedCapacity |= normalizedCapacity >>>  1;
            // 0b 0000 0000 0000 0000 0000 0011 0011 1111
            // 0b 0000 0000 0000 0000 0000 0000 1100 1111
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            normalizedCapacity |= normalizedCapacity >>>  2;
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            // 0b 0000 0000 0000 0000 0000 0000 0011 1111
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            normalizedCapacity |= normalizedCapacity >>>  4;
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            // 0b 0000 0000 0000 0000 0000 0000 0000 0011
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            normalizedCapacity |= normalizedCapacity >>>  8;
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111
            // 0b 0000 0000 0000 0000 0000 0000 0000 0000
            // 0b 0000 0000 0000 0000 0000 0011 1111 1111   =>  十进制 1023
            normalizedCapacity |= normalizedCapacity >>> 16;

            // 1024
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }

            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;
            // 上面算法 执行过之后 得到一个 >= 当前reqCapacity 的最小2的次方数
            // 比如： req = 512 返回 512
            //       req = 513 返回 1024
            //       req = 1025 返回 2048 ....
            return normalizedCapacity;
        }

        // 执行到这里，说明 reqCapacity < 512 ，reqCapacity 规格为 tiny
        // 不考虑...
        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
        // 判断当前req 是否是 16 32 48 64... 算法
        // 假设 req = 32 => 0b 0010 0000
        // 0b 0010 0000
        // 0b 0000 1111 => 0
        //

        // 假设 req = 19 => 0b 0001 0011
        // 0b 0001 0011
        // 0b 0000 1111 => 3
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        // 执行到这里，说明 req 的值 不是 16 32 48 ... 这种数字 ，可能是 17  33 52...
        // 这种情况需要取一个 > req  的最小 规格值。
        // 比如：req = 17 => 0b 0001 0001
        // 15 => 0b 1111
        // ~ 15 => 0b 1111 1111 1111 1111 1111 1111 1111 0000
        // 0b 0000 0000 0000 0000 0000 0000 0001 0001
        // 0b 1111 1111 1111 1111 1111 1111 1111 0000
        // 0b 0000 0000 0000 0000 0000 0000 0001 0000  => 16
        // 16 + 16 => 32

        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // This does not touch buf's reader/writer indices
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        // 参数1：allocator 对象
        // 参数2：pageSize,8k
        // 参数3：maxOrder，11
        // 参数4：pageShifts,13 ，1 << 13 => pageSize
        // 参数5：chunkSize,16mb
        // 参数6：directMemoryCacheAlignment 0
        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {

            // 参数1：allocator 对象
            // 参数2：pageSize,8k
            // 参数3：maxOrder，11
            // 参数4：pageShifts,13 ，1 << 13 => pageSize
            // 参数5：chunkSize,16mb
            // 参数6：directMemoryCacheAlignment 0
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        // 参数1：pageSize 8k
        // 参数2：maxOrder 11
        // 参数3：pageShifts 13
        // 参数4：chunkSize 16mb
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {

            // 正常情况 这个条件会成立。
            if (directMemoryCacheAlignment == 0) {

                // 参数1：this, 当前directArena对象，poolChunk对象需要知道它爸爸是谁。
                // 参数2：allocateDirect(chunkSize) 非常重要。 这个方法使用unsafe的方式 完成 DirectByteBuffer 内存的申请，申请多少？ 16mb 返回给咱们
                // ByteBuffer 对象。
                // 参数3：pageSize 8k
                // 参数4：maxOrder 11
                // 参数5：pageShifts 13
                // 参数6：chunkSize 16mb
                // 参数7： offset 0
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }

            // 不会执行下面代码..

            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
