package io.github.hello09x.fakeplayer.core.manager.naming;

import java.util.TreeSet;

public class NameSource {

    /**
     * 接下来可以使用的名称序号
     */
    private final TreeSet<Integer> names;

    /**
     * 容量
     */
    private int nextSequence;

    public NameSource(int initializeCapacity) {
        // Do not materialize the configured limit. A limit of zero means
        // unlimited and is represented by Integer.MAX_VALUE in the config;
        // eagerly allocating that many integers used to be an easy OOM.
        this.nextSequence = 0;
        this.names = new TreeSet<>();
    }

    public NameSource() {
        this(0);
    }

    /**
     * 获取一个可使用的名称序号
     *
     * @return 名称序号
     */
    public synchronized int pop() {
        if (!names.isEmpty()) {
            return names.pollFirst();
        }
        if (nextSequence == Integer.MAX_VALUE) {
            throw new IllegalStateException("Fake-player name sequence exhausted");
        }
        return nextSequence++;
    }

    /**
     * 归还一个名称序号
     *
     * @param i 名称序号
     */
    public synchronized void push(int i) {
        if (i < 0 || i >= nextSequence) {
            return;
        }
        names.add(i);
    }


}
