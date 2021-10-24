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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;
    final int offset;
    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    private int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;


    // 参数1：this, 当前directArena对象，poolChunk对象需要知道它爸爸是谁。
    // 参数2：allocateDirect(chunkSize) 非常重要。 这个方法使用unsafe的方式 完成 DirectByteBuffer 内存的申请，申请多少？ 16mb 返回给咱们
    // ByteBuffer 对象。
    // 参数3：pageSize 8k
    // 参数4：maxOrder 11
    // 参数5：pageShifts 13
    // 参数6：chunkSize 16mb
    // 参数7： offset 0
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        // 让chunk直接持有 byteBuffer 对象。
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;

        // chunk使用一颗满二叉树表示内存的占用情况，二叉树中每个节点 有三个维度的数据（深度，ID，可分配深度值 初始值与深度一致），
        // unusable 表示 当某个节点上的内存被分配出去后，它的可分配深度值 就需要改为 unusable 。以后就不可以再从这个节点上分配内存了。
        unusable = (byte) (maxOrder + 1);

        // 0b 1 0000 0000 0000 0000 0000 0000
        // 24
        //  计算有多少个0
        log2ChunkSize = log2(chunkSize);

        // 8k - 1
        //(pageSize - 1) =>  0b 0000 0000 0000 0000 0001 1111 1111 1111
        // ~(0b 0000 0000 0000 0000 0001 1111 1111 1111)
        //   0b 1111 1111 1111 1111 1110 0000 0000 0000
        // 这个数有什么用？ 当从chunk申请 >= 1page 时，申请的容量与 subpageOverflowMask 进行位于运算 会得到一个非0值。
        subpageOverflowMask = ~(pageSize - 1);

        // 初始时 就是 16mb ，默认情况。
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        // 最多可申请多少 subpage ， 1 << 11 => 2048
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.


        // 使用一个数组表示 满二叉树，根据公式 i 的左节点 2i ,i 的右节点 2i + 1

        // 创建了一个 长度为 4096 的数组。
        // 数组的每个元素 表示 当前i 下标 的树节点 的可分配 深度能力值，每个树节点的 可分配深度能力值 为 深度值，
        // 比如 memoryMap[1] = 0， 表示根节点可分配整个内存， memoryMap[2] = 1 表示这个节点可管理chunk的一半内存....
        // 注意：memoryMap 对应索引的 树节点 如果管理的内存被划分出去后，该值会改变。
        // [0, 0, 1, 1, 2, 2, 2, 2, 3 ,3 ,3 ,3 ,3 ,3 ,3 ,3 ....]  数组下标的 0 位置 是不使用的
        memoryMap = new byte[maxSubpageAllocs << 1];


        // [0, 0, 1, 1, 2, 2, 2, 2, 3 ,3 ,3 ,3 ,3 ,3 ,3 ,3 ....]
        // 表示当前索引的树节点 所在树的深度是多少，注意：初始化之后 depthMap 就不会再发生改变了。
        depthMap = new byte[memoryMap.length];

        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }


        // 因为chunk默认情况下，最多可以开辟 2048 个 subpage ，所以 这里创建一个 长度为 2048 的subpage 数组。
        subpages = newSubpageArray(maxSubpageAllocs);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    // 参数1：buf 返回给业务使用ByteBuf对象，下面逻辑会给该buf 分配真正的内存。
    // 参数2：reqCapacity，业务层需要的内存容量
    // 参数3：将req 转换成的规格size。
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {

        // 非常重要...
        final long handle;

        // 条件成立：说明 normCapacity 是一个 >= pageSize 的值，也就说 业务需求的内存量是一个 大于等于 一个页的内存。
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            // 申请 8k 16k 32k... <= 16mb 逻辑的入口
            // 这里的handle 是 当前分配内存 占用的 树节点 id 值，可能是 -1 ，-1 表示申请失败。。
            handle =  allocateRun(normCapacity);
        } else {

            // 说明业务需要的内存 是 小规格内存：tiny 、small.
            handle = allocateSubpage(normCapacity);
        }

        // 执行到这里 会拿到一个 handle 值,该值可能是  allocateRun 也可能是 allocateSubpage 的返回值。

        if (handle < 0) {
            // handle == -1 说明  allocateRun  或者 allocateSubpage 申请内存失败..
            return false;
        }


        // 暂不考虑了... 认为是个 null 吧..
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;


        // 参数1：buf 返回给业务使用ByteBuf对象，它包装内存。
        // 参数2：nioBuffer 认为是个 null 吧.
        // 参数3：handle, allocateRun  或者 allocateSubpage  返回值。
        // 参数4：reqCapacity 业务层需要的内存容量
        initBuf(buf, nioBuffer, handle, reqCapacity);

        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        // 比如id = 2048 ，会影响 1024 ,512 , 256, 128 , 64, 32, 16, 8, 4, 2, 1

        while (id > 1) {
            // 2048 >>> 1 => 1024
            int parentId = id >>> 1;

            // 获取出 1024 节点 左右 子节点的能力值： 2048-> 12  2049-> 11
            byte val1 = value(id);
            byte val2 = value(id ^ 1);

            // 获取出 左右子节点 val 较小的值
            byte val = val1 < val2 ? val1 : val2;

            // 将父节点 设置为  较小的 val。
            setValue(parentId, val);
            // 更新id 为 父节点... 继续向上更新。。
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    // 参数： 内存大小 对应的 深度值。
    private int allocateNode(int d) {
        // id is 1 的节点为 根节点，allocateNode 方法是从根节点开始 向下查找一个 合适深度d 的节点 进行占用 完成内存分配。
        int id = 1;

        // 1 << 11 => 2048
        // - 2048 => 0b 1111 1111 1111 1111 1111 1000 0000 0000
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1

        // 获取chunk二叉树 根节点的 可分配深度能力值
        byte val = value(id);

        // 条件成立：说明当前 chunk 剩余的内存 不足以支撑本次内存申请。
        if (val > d) { // unusable
            // 返回 -1 表示申请失败..
            return -1;
        }


        // 深度优先 的一个 搜索算法。

        // 条件一：val < d， val 表示二叉树中某个节点的 可分配深度能力值， 条件成立 说明 当前节点管理的内存 比较大，总之大于 申请容量的，需要到当前节点的下一级
        // 尝试申请。
      //  -2048    0b 1111 1111 1111 1111 1111 1000 0000 0000
        // 2048 => 0b 0000 0000 0000 0000 0000 1000 0000 0000
        //  ==> 得出结果是 非 0

        //         0b 1111 1111 1111 1111 1111 1000 0000 0000
        // 1024 => 0b 0000 0000 0000 0000 0000 0100 0000 0000
        //  => 0
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            // 向下一级， 比如 id = 1 , id << 1 => 2
            // 向下一级， 比如 id = 2 , id << 1 => 4
            // 向下一级， 比如 id = 4 , id << 1 => 8
            // 向下一级， 比如 id = 8 , id << 1 => 16...
            // 向下一级， 比如 id = 1024 , id << 1 => 2048
            id <<= 1;
            // 获取当前节点的 可分配深度能力值
            val = value(id);

            if (val > d) {
                // 伙伴算法，获取兄弟节点 id
                // 比如： 2048 => 0b 1000 0000 0000
                //  0b 1000 0000 0000
                //  0b 0000 0000 0001
                //  0b 1000 0000 0001 => 2049
                id ^= 1;  //^ 亦或算法  不同为1  相同 为0
                // 获取兄弟节点的 可分配深度能力值。
                val = value(id);
            }
        }



        // 获取查询出来的合适分配节点的深度能力值。
        byte value = value(id);

        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 占用这个节点，就是将这个节点的 分配能力值 改为 unusable 。
        setValue(id, unusable); // mark as unusable

        // 因为当前节点的内存 被分配出去，影响了 当前节点的 父节点 爷爷节点...祖宗节点... 需要更新 他们的分配深度能力值。
        updateParentsAlloc(id);

        // 返回 分配给用户内存 占用的 node 节点 id。
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity 规格后的一个大小  8k 16k....
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 计算normCapacity容量大小的内存 需要到 深度为 d 的树节点上去分配内存。
        int d = maxOrder - (log2(normCapacity) - pageShifts);

        // 到满二叉树上去分配内存。
        // 参数： 内存大小 对应的 深度值。
        int id = allocateNode(d);

        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity   16b 32b 48b....  512b 1024b 2048b 4096b
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.

        // 参数1：规格后的elemSize
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);



        // d = 11 ,因为接下来要从chunk上申请一页内存。 咱们知道 二叉树叶子节点 管理一个页，所以 d 设置成 maxOrder 值。
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves


        // head 节点 作为 lock对象。
        synchronized (head) {

            // allocateNode 方法 根据传入的 深度值 占用一个 树节点，咱们这里传入的深度值 是 叶子节点的 深度值， 也就是 申请一个页，
            // 这里会返回 叶子节点的 id，或者是 -1 ，-1 说明 chunk 连一个页 也分配不了了...
            int id = allocateNode(d);

            if (id < 0) {
                // -1 在这里返回。
                return id;
            }


            // subpages 数组 是一个长度为 2048 的数组，保证chunk创建出来的 subpage 都有地方存放。
            final PoolSubpage<T>[] subpages = this.subpages;
            // 8k
            final int pageSize = this.pageSize;

            // 占用了 一页内存，就需要从chunk范围内 减去 8k
            freeBytes -= pageSize;


            // 取模算法：2048  => 0,  2049 => 1 ....  因为数组 0~2047
            int subpageIdx = subpageIdx(id);

            // 获取出数组 当前 位置的 subpage
            PoolSubpage<T> subpage = subpages[subpageIdx];


            // 正常情况 subpage == null 。
            if (subpage == null) {
                // 看这里。

                // 参数1：head arena范围内的 pools 符合当前规格的 head 节点
                // 参数2：this， 当前chunk 对象，因为 作为 subpage 它得知道它爸爸是谁
                // 参数3：id， 当前subpage 占用的 叶子节点的 id 号。
                // 参数4：runOffset(id) 计算出当前叶子节点 的 管理的内存在整个内存的偏移位置。
                // 参数5：pageSize,8k
                // 参数6: normCapacity, 16b 32b .... 512b 1024b... 4096b
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);

                subpages[subpageIdx] = subpage;

            } else {
                subpage.init(head, normCapacity);
            }

            // 申请小规格内存的入口
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 计算出 待归还内存 占用的 树节点 ID
        int memoryMapIdx = memoryMapIdx(handle);
        // 计算出 待归还内存 占用的 bit 索引值
        int bitmapIdx = bitmapIdx(handle);


        if (bitmapIdx != 0) { // free a subpage    条件成立：说明 待归还内存 是 tiny 或者 small 这两种类型 内存。
            // 根据 handle 计算出来的 subpage 占用 叶子节点 ID  获取出来  subpage 对象。
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];

            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获取 出 arena 范围 该 内存规格 在 pools 的 head 节点。
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                // 释放内存。
                // 参数1：获取 出 arena 范围 该 内存规格 在 pools 的 head 节点。
                // 参数2：bitmapIdx & 0x3FFFFFFF  ==》 计算出 内存占用 位图的 真实索引值。
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }



        freeBytes += runLength(memoryMapIdx);

        setValue(memoryMapIdx, depth(memoryMapIdx));

        //更新父节点信息
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    // 参数1：buf 返回给业务使用ByteBuf对象，它包装内存。
    // 参数2：nioBuffer 认为是个 null 吧.
    // 参数3：handle, allocateRun  或者 allocateSubpage  返回值。
    // 参数4：reqCapacity 业务层需要的内存容量
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {

        // 获取handle 低32位的值。 allocateRun   allocateSubpage  返回值的低32位 都是表示  树节点 的 id值。
        int memoryMapIdx = memoryMapIdx(handle);
        // 获取handle 高32位的值。  allocateRun 高32 位 是0.   allocateSubpage 高32位 是 bitmapIdx | 0x 4000000000000000
        int bitmapIdx = bitmapIdx(handle);

        // 条件成立：说明 handle 表示的 是 allocateRun 的返回值，接下来是处理 allocateRun 内存的封装逻辑。
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);

            // 参数1：this ，创建byteBuf 分配内存的 chunk 对象，真实的内存 是由chunk 持有的，所以 必须传 chunk对象
            // 参数2：nioBuffer 可能是null..
            // 参数3：handle  buf 占用的内存 位置相关信息 都与 handle 值有关系，后面 释放内存时 还要使用 handle 的值。
            // 参数4：runOffset(memoryMapIdx) + offset   计算出 当前buf 占用的内存 在 byteBuffer 上的偏移位置，必须知道buf管理的内存 在 大内存上的偏移位置 ，
            // 很重要。
            // 参数5：reqCapacity ,赋值给 buf length 属性了，表示 业务申请的内存大小。
            // 参数6：runLength(memoryMapIdx)   比如：memoryMapIdx = 2048   返回 8k  ， memoryMapIdx = 1024   返回 16k ... ，赋值给buf
            // maxLength 字段，表示 buf 可用内存的最大 大小。
            // 参数7：当前线程的 threadLocalCache 对象，为什么要传这个对象？  因为释放的时候 首选 释放 的地方 为 threadLocalCache ,缓存到 线程局部，方便
            // 后面再申请 时 使用，而不是直接归还到 pool。
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            // 说明 handle 表示的 是 allocateSubpage 的返回值，接下来是处理 allocateSubpage 内存的封装逻辑。

            // 参数1：返回给业务使用ByteBuf对象，它包装内存。
            // 参数2：nioBuffer  可能是null..
            // 参数3：handle  buf 占用的内存 位置相关信息 都与 handle 值有关系，后面 释放内存时 还要使用 handle 的值。
            // 参数4：bitmapIdx ，从高位的第二位 是 标记位 这里是 1.   ===》 bitmapIdx | 0x 4000000000000000
            // 参数5：reqCapacity，赋值给 buf length 属性了，表示 业务申请的内存大小。
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    // 参数1：返回给业务使用ByteBuf对象，它包装内存。
    // 参数2：nioBuffer  可能是null..
    // 参数3：handle  buf 占用的内存 位置相关信息 都与 handle 值有关系，后面 释放内存时 还要使用 handle 的值。
    // 参数4：bitmapIdx ，从高位的第二位 是 标记位 这里是 1.   ===》 bitmapIdx | 0x 4000000000000000
    // 参数5：reqCapacity，赋值给 buf length 属性了，表示 业务申请的内存大小。
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        // 获取 subpage 占用的叶子节点 id
        int memoryMapIdx = memoryMapIdx(handle);

        // 获取出 给buf 分配内存的 subpage 对象
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];

        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // 参数1：this， 给buf分配内存的chunk对象，因为chunk是真正持有 byteBuffer 大内存的对象。
        // 参数2：nioBuffer   可能是null..
        // 参数3：handle buf 占用的内存 位置相关信息 都与 handle 值有关系，后面 释放内存时 还要使用 handle 的值。
        // 参数4：runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset
        // 参数5：reqCapacity ， 赋值给 buf length 属性了，表示 业务申请的内存大小。
        // 参数6：maxLength， subpage.elemSize  规格大小
        // 参数7：当前线程的 poolThreadCache 对象，后面释放内存 优先 将内存位置信息 缓存到 poolThreadCache 内，方面后面当前线程再次获取相同规格内存。
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());




        // 参数4：runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset   详解。
        // runOffset(memoryMapIdx) =》  计算出来 subpage 占用的内存 在 byteBuffer上的偏移位置。
        // (bitmapIdx & 0x3FFFFFFF) =》0x3FFFFFFF => 0b 0011 1111 1111 1111 1111 1111 1111 1111
        // 例如： bitmapIdx is 68 =》 0b 0100 0000 0000 0000 0000 0000 0100 0100  68 是后面的前面进行了高位第二位 补1
        // 0b 0100 0000 0000 0000 0000 0000 0100 0100
        // 0b 0011 1111 1111 1111 1111 1111 1111 1111
        // 0b 0000 0000 0000 0000 0000 0000 0100 0100
        // 也就是说 (bitmapIdx & 0x3FFFFFFF)  在做解码操作，解析出来 真正的 bitmapIdx 值。！！！！！

        // bitmapIdx * subpage.elemSize   就计算出来 当前 bitmapIdx 占用的内存 在 subpage 上的偏移位置。

        // runOffset(memoryMapIdx) 计算出来了 subpage 在chunk上的偏移位置
        // (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize 计算出来了  bitmapIdx 在subpage上的偏移位置
        // 俩加起来  就是 业务申请的这一小块 内存 在 byteBuffer 大内存上的 正确偏移位置。
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        // log2ChunkSize = 24
        // 24 - depth(2049) => 24 - 11 => 13
        // 1 << 13 => 8192
        return 1 << log2ChunkSize - depth(id);
    }

    // 这里假设  id = 2049
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        // depth(2049)  => 11
        // 1 << 11 => 0b 1000 0000 0000
        // 1000 0000 0001
        // 1000 0000 0000
        // 0000 0000 0001
        // shift => 1
        // 所以：id = 2048 => shift is 0
        // 所以：id = 2049 => shift is 1
        // 所以：id = 2050 => shift is 2
        // 所以：...
        int shift = id ^ 1 << depth(id);


        // runLength(id) => 计算出 id (2049) 对应管理的内存长度为 8k
        // 1 * 8k => 8k
        return shift * runLength(id);

        // 结论：
        // id（2048） 返回 offset 0
        // id（2049） 返回 offset 8k
        // id（2050） 返回 offset 16k
        // id（2051） 返回 offset 24k

    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
