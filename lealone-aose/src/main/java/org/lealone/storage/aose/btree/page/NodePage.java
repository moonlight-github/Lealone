/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage.aose.btree.page;

import java.nio.ByteBuffer;

import org.lealone.common.util.DataUtils;
import org.lealone.db.DataBuffer;
import org.lealone.storage.aose.btree.BTreeMap;
import org.lealone.storage.aose.btree.chunk.Chunk;
import org.lealone.storage.aose.btree.page.PageOperations.TmpNodePage;

public class NodePage extends LocalPage {

    // 对子page的引用，数组长度比keys的长度多一个
    private PageReference[] children;

    NodePage(BTreeMap<?, ?> map) {
        super(map);
    }

    @Override
    public boolean isNode() {
        return true;
    }

    @Override
    public boolean isEmpty() {
        return children == null || children.length == 0;
    }

    @Override
    public PageReference[] getChildren() {
        return children;
    }

    @Override
    public PageReference getChildPageReference(int index) {
        return children[index];
    }

    @Override
    public Page getChildPage(int index) {
        PageReference ref = children[index];
        Page p = ref.page; // 先取出来，GC线程可能置null
        if (p != null) {
            p.updateTime();
            return p;
        } else {
            PageInfo pInfo = ref.pInfo;
            if (pInfo != null && pInfo.buff != null) {
                p = map.getBTreeStorage().readPage(ref, ref.pos, pInfo.buff, pInfo.pageLength);
                map.getBTreeStorage().gcIfNeeded(p.getMemory());
            } else {
                p = map.getBTreeStorage().readPage(ref);
                ref.pInfo = p.pInfo;
            }
            ref.replacePage(p);
            return p;
        }
    }

    @Override
    NodePage split(int at) { // at对应的key只放在父节点中
        int a = at, b = keys.length - a;
        Object[] aKeys = new Object[a];
        Object[] bKeys = new Object[b - 1];
        System.arraycopy(keys, 0, aKeys, 0, a);
        System.arraycopy(keys, a + 1, bKeys, 0, b - 1);
        keys = aKeys;

        // children的长度要比keys的长度多1并且右边所有leaf的key都大于或等于at下标对应的key
        PageReference[] aChildren = new PageReference[a + 1];
        PageReference[] bChildren = new PageReference[b];
        System.arraycopy(children, 0, aChildren, 0, a + 1);
        System.arraycopy(children, a + 1, bChildren, 0, b);
        children = aChildren;

        NodePage newPage = create(map, bKeys, bChildren, 0);
        recalculateMemory();
        return newPage;
    }

    @Override
    public long getTotalCount() {
        long totalCount = 0;
        for (PageReference x : children) {
            if (x.page != null)
                totalCount += x.page.getTotalCount();
        }
        return totalCount;
    }

    @Override
    Page copyAndInsertChild(TmpNodePage tmpNodePage) {
        int index = getPageIndex(tmpNodePage.key);
        Object[] newKeys = new Object[keys.length + 1];
        DataUtils.copyWithGap(keys, newKeys, keys.length, index);
        newKeys[index] = tmpNodePage.key;

        PageReference[] newChildren = new PageReference[children.length + 1];
        DataUtils.copyWithGap(children, newChildren, children.length, index);
        newChildren[index] = tmpNodePage.left;
        newChildren[index + 1] = tmpNodePage.right;

        tmpNodePage.left.setParentRef(getRef());
        tmpNodePage.right.setParentRef(getRef());
        int memory = map.getKeyType().getMemory(tmpNodePage.key) + PageUtils.PAGE_MEMORY_CHILD;
        return copy(newKeys, newChildren, getMemory() + memory, true);
    }

    @Override
    public void remove(int index) {
        if (keys.length > 0) // 删除最后一个children时，keys已经空了
            super.remove(index);
        addMemory(-PageUtils.PAGE_MEMORY_CHILD);
        int childCount = children.length;
        PageReference[] newChildren = new PageReference[childCount - 1];
        DataUtils.copyExcept(children, newChildren, childCount, index);
        children = newChildren;
    }

    @Override
    public void read(ByteBuffer buff, int chunkId, int offset, int expectedPageLength,
            boolean disableCheck) {
        int start = buff.position();
        int pageLength = buff.getInt();
        checkPageLength(chunkId, pageLength, expectedPageLength);
        readCheckValue(buff, chunkId, offset, pageLength, disableCheck);

        int keyLength = DataUtils.readVarInt(buff);
        keys = new Object[keyLength];
        int type = buff.get();
        children = new PageReference[keyLength + 1];
        long[] p = new long[keyLength + 1];
        for (int i = 0; i <= keyLength; i++) {
            p[i] = buff.getLong();
        }
        for (int i = 0; i <= keyLength; i++) {
            int pageType = buff.get();
            if (pageType == 0)
                buff.getInt(); // replicationHostIds
            children[i] = new PageReference(null, p[i]);
            children[i].setParentRef(getRef());
        }
        buff = expandPage(buff, type, start, pageLength);

        map.getKeyType().read(buff, keys, keyLength);
        recalculateMemory();
    }

    /**
    * Store the page and update the position.
    *
    * @param chunk the chunk
    * @param buff the target buffer
    * @return the position of the buffer just after the type
    */
    private int write(Chunk chunk, DataBuffer buff) {
        int start = buff.position();
        int keyLength = keys.length;
        buff.putInt(0);
        int checkPos = buff.position();
        buff.putShort((short) 0).putVarInt(keyLength);
        int typePos = buff.position();
        int type = PageUtils.PAGE_TYPE_NODE;
        buff.put((byte) type);
        writeChildrenPositions(buff);
        for (int i = 0; i <= keyLength; i++) {
            if (children[i].isLeafPage()) {
                buff.put((byte) 0);
                buff.putInt(0); // replicationHostIds
            } else {
                buff.put((byte) 1);
            }
        }
        int compressStart = buff.position();
        map.getKeyType().write(buff, keys, keyLength);

        compressPage(buff, compressStart, type, typePos);

        int pageLength = buff.position() - start;
        buff.putInt(start, pageLength);
        int chunkId = chunk.id;

        writeCheckValue(buff, chunkId, start, pageLength, checkPos);

        updateChunkAndPage(chunk, start, pageLength, type);

        removeIfInMemory();
        return typePos + 1;
    }

    private void writeChildrenPositions(DataBuffer buff) {
        for (int i = 0, len = keys.length; i <= len; i++) {
            buff.putLong(children[i].pos); // pos通常是个很大的long，所以不值得用VarLong
        }
    }

    @Override
    public void writeUnsavedRecursive(Chunk chunk, DataBuffer buff) {
        if (pos != 0) {
            // already stored before
            return;
        }
        int patch = write(chunk, buff);
        for (int i = 0, len = children.length; i < len; i++) {
            Page p = children[i].page;
            if (p != null) {
                p.writeUnsavedRecursive(chunk, buff);
                children[i].pos = p.pos;
            }
            // 释放资源
            children[i].page = null;
            children[i].pInfo = null;
        }
        int old = buff.position();
        buff.position(patch);
        writeChildrenPositions(buff);
        buff.position(old);
    }

    @Override
    public int getRawChildPageCount() {
        return children.length;
    }

    @Override
    protected void recalculateMemory() {
        int mem = recalculateKeysMemory();
        mem += this.getRawChildPageCount() * PageUtils.PAGE_MEMORY_CHILD;
        addMemory(mem - memory);
    }

    @Override
    public NodePage copy() {
        return copy(true);
    }

    private NodePage copy(boolean removePage) {
        return copy(keys, children, getMemory(), removePage);
    }

    private NodePage copy(Object[] keys, PageReference[] children, int memory, boolean removePage) {
        NodePage newPage = create(map, keys, children, memory);
        newPage.cachedCompare = cachedCompare;
        newPage.setRef(getRef());
        if (removePage) {
            // mark the old as deleted
            removePage();
        }
        return newPage;
    }

    static NodePage create(BTreeMap<?, ?> map, Object[] keys, PageReference[] children, int memory) {
        NodePage p = new NodePage(map);
        // the position is 0
        p.keys = keys;
        p.children = children;
        if (memory == 0) {
            p.recalculateMemory();
        } else {
            p.addMemory(memory);
        }
        return p;
    }

    @Override
    protected void getPrettyPageInfoRecursive(StringBuilder buff, String indent, PrettyPageInfo info) {
        if (children != null) {
            buff.append(indent).append("children: ").append(keys.length + 1).append('\n');
            for (int i = 0, len = keys.length; i <= len; i++) {
                buff.append('\n');
                if (children[i].page != null) {
                    children[i].page.getPrettyPageInfoRecursive(indent + "  ", info);
                } else {
                    if (info.readOffLinePage) {
                        map.getBTreeStorage().readPage(children[i])
                                .getPrettyPageInfoRecursive(indent + "  ", info);
                    } else {
                        buff.append(indent).append("  ");
                        buff.append("*** off-line *** ").append(children[i]).append('\n');
                    }
                }
            }
        }
    }
}
