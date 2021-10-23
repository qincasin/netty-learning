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

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    // 参数1：head arena范围内的 pools 符合当前规格的 head 节点
    // 参数2：this， 当前chunk 对象，因为 作为 subpage 它得知道它爸爸是谁
    // 参数3：id， 当前subpage 占用的 叶子节点的 id 号。
    // 参数4：runOffset(id) 计算出当前叶子节点 的 管理的内存在整个内存的偏移位置。
    // 参数5：pageSize,8k
    // 参数6: normCapacity, 16b 32b .... 512b 1024b... 4096b
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {

        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;

        // pageSize / 16 是因为 tiny 最小规格 16b，以最小规格划分的话 需要多少个 bit 才能表示出这个 内存的使用情况。
        // / 64 是因为 long 是8 字节，1 字节 是8bit ，计算出 多少 long 才能表示整个位图。
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64      => 8

        // 参数1：head arena范围内的 pools 符合当前规格的 head 节点
        // 参数2：elemSize, 16b 32b .... 512b 1024b... 4096b
        init(head, elemSize);

    }

    void init(PoolSubpage<T> head, int elemSize) {
        // true 表示当前subpage 是存活状态， false 表示当前subpage 是释放状态，不可用状态。
        doNotDestroy = true;

        // subpage 需要知道它管理的 内存规格 size。
        this.elemSize = elemSize;


        if (elemSize != 0) {
            // 正常都会 执行 if 内。

            // pageSize / elemSize ，计算什么？ 计算出subpage按照 elemSize 划分 一共可以划分多少 小块。
            // 比如： elemSize = 32b => 256
            // 比如： elemSize = 48b => 170

            // maxNumElems 赋值之后 就不在变动了，表示当前 subpage 最多可给业务分配多少小块内存。
            // numAvail，每对外划分出一小块 内存后，该值 都减一。
            maxNumElems = numAvail = pageSize / elemSize;

            // nextAvail 申请内存时  会使用这个字段。 表示 下一个 可用的 位图下标值。
            nextAvail = 0;

            // maxNumElems / 64  计算出当前 maxNumElems 需要 long 数组的 多少。
            // 当 elemSize = 32b 时， 计算出来  bitmapLength = 4
            // 当 elemSize = 48b 时， 计算出来  bitmapLength = 2 ，但是你知道 170 > 128
            bitmapLength = maxNumElems >>> 6;

            // 条件成立：说明 maxNumElems 它不是一个 64 整除的数，需要的bitmapLength 再加一。
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            // 初始化 bitmap 的值,设置成0
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }

        // 将当前新创建出来的 subpage 对象 插入到 arena 范围的 pools 符合当前 规格的 head 节点的 下一个位置。
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 条件一：numAvail == 0 ,说明当前subpage管理的 一页内存 全部划分出去了，没有空闲内存空划分了。
        // 条件二：doNotDestroy ,初始时是 true ... 当subpage 释放内存后，doNotDestroy 会改成 false
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 从bitmap内查找一个 可用的 bit ，返回该bit的索引值。
        final int bitmapIdx = getNextAvail();

        //下面逻辑 将 bitmapIdx 表示的 bit 设置为 1，表示这块小内存 已经划分出去了..


        // 代入法： bitmapIdx is 68   => q = 1,    bitmapIdx is 16  => q = 0
        int q = bitmapIdx >>> 6;
        // 代入法： bitmapIdx is 68  => r = 4
        int r = bitmapIdx & 63;

        assert (bitmap[q] >>> r & 1) == 0;
        // 代入法： r = 4  => 1 << 4  => 0b 1 0000
        // bitmap[1] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111
        //             0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 0000
        // bitmap[1] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 1111
        bitmap[q] |= 1L << r;

        // --numAvail 因为刚刚划分出去 了 一小块，所以 减一
        // 减一之后 如果 是 0 了，说明当前这个poolSubpage 管理的 这块 page 完全划分完了...
        if (-- numAvail == 0) {
            // 从arena内 移除，因为当前 subpage 管理的 内存 完全划分完了，其它业务没办法再从 该 subpage 上申请空间了..
            removeFromPool();
        }

        // 参数：bitmapIdx 业务划分出去的内存 对应 位图的 索引值。
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    // 参数1：获取 出 arena 范围 该 内存规格 在 pools 的 head 节点。
    // 参数2：bitmapIdx & 0x3FFFFFFF  ==》 计算出 内存占用 位图的 真实索引值。
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // nextAvail 初始值给的是0，当某个byteBuf占用的内存 还给 当前subpage 时，这个内存占用的bit 的索引值 会设置给 this.nextAvail ,下次再申请
        // 直接就使用 nextAvail 就可以了
        int nextAvail = this.nextAvail;

        if (nextAvail >= 0) {
            this.nextAvail = -1;
            // 1. 初始时  nextAvail = 0
            // 2. 其它byteBuf 归还内存时  会设置 nextAvail 为它占用的 那块内存对应的 bit 索引值。
            return nextAvail;
        }

        // 普通情况 都走这里。
        return findNextAvail();
    }

    private int findNextAvail() {
        // 位图
        final long[] bitmap = this.bitmap;
        // 位图数组的有效长度
        final int bitmapLength = this.bitmapLength;




        // 假设 当前 subpage(规格是 32b ) 对外分配了 68 块 小内存，并且 这68块小内存 都没归还给 subpage ，那它的位图 长什么样？
        // bitmap[0] = 0b 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
        // bitmap[1] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111
        // bitmap[2] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
        // bitmap[3] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];

            // 条件不成立说明：bitmap[i] 表示的 内存 全部分配出去了...没办法在 bitmap[i] 的这个 bitmap上分配了。

            if (~bits != 0) {
                // 说明 bitmap[i] 还有空余空间 可以给当前这次 allocate 去支撑。
                // findNextAvail0 去查找一个 空闲内存的 bitmap 索引值。

                // 参数1：i
                // 参数2：bitmap[i] 表示的这一小块 bitmap
                return findNextAvail0(i, bits);
            }
        }

        // 返回-1 说明整个 subpage 都被占用完毕了...无法完成分配。
        return -1;
    }

    // 参数1：i
    // 参数2：bitmap[i] 表示的这一小块 bitmap
    private int findNextAvail0(int i, long bits) {

        // 当前subpage 最多可对外分配的内存块数， 假设当前subpage 规格为 32b ，那么 这个maxNumElems = 256
        final int maxNumElems = this.maxNumElems;

        // 假设 当前 subpage(规格是 32b ) 对外分配了 68 块 小内存，并且 这68块小内存 都没归还给 subpage ，那它的位图 长什么样？
        // bitmap[0] = 0b 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
        // bitmap[1] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111
        // bitmap[2] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
        // bitmap[3] = 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

        // i << 6 === i * 64
        final int baseVal = i << 6;


        // for循环需要从 bitmap[1] 中找到第一个可以用的 bit 位置, 返回给业务..
        for (int j = 0; j < 64; j ++) {
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001   =》 1  第一次条件不成立。

            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001   =》 1  第二次条件不成立。

            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0011
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001   =》 1  第三次条件不成立。

            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001   =》 1  第四次条件不成立。

            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
            //  0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001   =》 0  第五次条件成立。
            if ((bits & 1) == 0) {
                // 0b 0100 0000
                // 0b 0000 0100
                // 0b 0100 0100
                // 相当于做了一个 加法运算。 因为baseVal 低位 全部是0，可以使用 或的方式 进行加法运算。
                int val = baseVal | j;

                if (val < maxNumElems) {
                    // 返回 68 这个位图坐标值。
                    return val;
                } else {
                    break;
                }


            }

            // 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111,  j=0
            // 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0011,  j=1
            // 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001,  j=2
            // 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000,  j=3...本次循环完之前 j ++ 变成4 了。
            bits >>>= 1;
        }

        return -1;
    }

    // 参数：bitmapIdx 业务划分出去的内存 对应 位图的 索引值。
    private long toHandle(int bitmapIdx) {

        // 假设 bitmapIdx = 68
        // (long) bitmapIdx << 32
        // 0b 0100 0100 => 0b 0000 0000 0000 0000 0000 0000 0100 0100 0000 0000 0000 0000 0000 0000 0000 0000

        // memoryMapIdx 是什么？ 当前subpage占用chunk的叶子节点 ID 值。
        // 假设当前subpage 占用的 叶子节点 id 为： 2049
        // 2049 => 0b 1000 0000 0001

        // 0b 0000 0000 0000 0000 0000 0000 0100 0100 0000 0000 0000 0000 0000 0000 0000 0000
        // 0b 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1000 0000 0001
        // 0b 0000 0000 0000 0000 0000 0000 0100 0100 0000 0000 0000 0000 0000 1000 0000 0001
        // 高32位 是 业务占用subpage内存的 bit 索引值   低 32位 为当前 subpage 占用chunk叶子节点 id 值。

        // 0x4000000000000000L => 0100 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
        // 除符号位 之外，最高位 为1 其它位全部都是0.
        // 0b 0000 0000 0000 0000 0000 0000 0100 0100 0000 0000 0000 0000 0000 1000 0000 0001
        // 0b 0100 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
        // 0b 0100 0000 0000 0000 0000 0000 0100 0100 0000 0000 0000 0000 0000 1000 0000 0001
        // 为什么要这样搞？？
        // 因为后面逻辑 需要根据 handle 值 创建 byteBuf 对象，需要根据 handle 计算出 byteBuf 共享chunk ByteBuffer 内存的 偏移位置。
        // allocateRun 和 allocateSubpage 申请的 内存 ，计算规则完全不一致，需要 根据 handle 的标记位 进行不同的逻辑处理。
        // 当subpage 第一次 对外 分配 内存时，返回的 handle 如果没有标记位的 话，会与 allocate 产生 冲突。
        // 比如： subpage 占用的 叶子节点是  2048 ,  第一次对外 分配内存 返回的值为： 高32位 是 0， 低 32 位 是 2048  =》 2048
        // 没有标记位 就和 allocate 冲突了....

        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
