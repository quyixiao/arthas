package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.util.ArrayUtils;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ThreadUtil;
import com.taobao.arthas.core.util.affect.Affect;
import com.taobao.arthas.core.util.affect.RowAffect;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.text.renderers.ThreadRenderer;
import com.taobao.text.ui.LabelElement;
import com.taobao.text.util.RenderUtil;

import java.lang.Thread.State;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * @author hengyunabc 2015年12月7日 下午2:06:21
 *
 *
 * 查看当前线程信息，查看线程的堆栈
 *
 *
 * 支持一键展示当前最忙的n个线程并打印堆栈信息
 * thread -n 3
 *  找出当前阻塞其他的线程的线程
 *  thread -b
 *  有个时候，我们发现应用卡住了，通常是由于某个线程拿住了线程的某个锁，并且其他的线程都在等待这把锁造成的，为了排查这类问题，arthas
 *  提供了-b，一键找出那个问题
 *
 * thread -i ，指定时间间隔
 * thread -n 3 -i 1000
 *
 *
 *
 *
 *
 *
 *
 */
@Name("thread")
@Summary("Display thread info, thread stack")
@Description(Constants.EXAMPLE +
        "  thread\n" +
        "  thread 51\n" +
        "  thread -n -1\n" +
        "  thread -n 5\n" +
        "  thread -b\n" +
        "  thread -i 2000\n" +
        Constants.WIKI + Constants.WIKI_HOME + "thread")
public class ThreadCommand extends AnnotatedCommand {

    private static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private long id = -1;
    private Integer topNBusy = null;
    private boolean findMostBlockingThread = false;
    private int sampleInterval = 100;

    @Argument(index = 0, required = false, argName = "id")              //线程id
    @Description("Show thread stack")
    public void setId(long id) {
        this.id = id;
    }

    @Option(shortName = "n", longName = "top-n-threads")                    //指定最忙的线程打印堆栈信息
    @Description("The number of thread(s) to show, ordered by cpu utilization, -1 to show all.")
    public void setTopNBusy(Integer topNBusy) {
        this.topNBusy = topNBusy;
    }

    @Option(shortName = "b", longName = "include-blocking-thread", flag = true)            //找出当前阻塞其他线程的线程
    @Description("Find the thread who is holding a lock that blocks the most number of threads.")
    public void setFindMostBlockingThread(boolean findMostBlockingThread) {
        this.findMostBlockingThread = findMostBlockingThread;
    }

    //指定cpu占统计的采样间隔，单位为毫秒
    //这里cpu统计的是，一段采样的间隔内，当前jvm里和个线程所占用的cpu时间的百分比，其计算方法为，首先进行一次采样，获得所有的线程cpu的使用时间
    //调用的是java.lang.management.tHeadMXBean#geThreadCpuTime这个接口，然后睡眠一段时间，默认是100朵，可以通过-i参数指定，然后再
    // 来一次采访一样，最后得出的这段时间内各个线程消耗的CPU的时间情况，最后算出百分比
    // 注意，这个统计也会产生一定的开销，JDK 这个接口本身开销比较大，因此会看到as的线程占用一定的百分比，为了降低统计自身的开销带来影响
    // 可以采样时间拉长一些，比如50000毫秒
    // 如果想看从java进程启动开始到现在的cpu占比的情况，可以使用show-busy-java-threads
    //
    @Option(shortName = "i", longName = "sample-interval")
    @Description("Specify the sampling interval (in ms) when calculating cpu usage.")
    public void setSampleInterval(int sampleInterval) {
        this.sampleInterval = sampleInterval;
    }

    @Override
    public void process(CommandProcess process) {
        Affect affect = new RowAffect();
        try {
            if (id > 0) {
                processThread(process);
            } else if (topNBusy != null) {
                processTopBusyThreads(process);
            } else if (findMostBlockingThread) {
                processBlockingThread(process);
            } else {
                processAllThreads(process);
            }
        } finally {
            process.write(affect + "\n");
            process.end();
        }
    }

    private void processAllThreads(CommandProcess process) {
        Map<String, Thread> threads = ThreadUtil.getThreads();

        // 统计各种线程状态
        StringBuilder threadStat = new StringBuilder();
        Map<State, Integer> stateCountMap = new HashMap<State, Integer>();
        for (State s : State.values()) {
            stateCountMap.put(s, 0);
        }

        for (Thread thread : threads.values()) {
            State threadState = thread.getState();
            Integer count = stateCountMap.get(threadState);
            stateCountMap.put(threadState, count + 1);
        }

        threadStat.append("Threads Total: ").append(threads.values().size());
        for (State s : State.values()) {
            Integer count = stateCountMap.get(s);
            threadStat.append(", ").append(s.name()).append(": ").append(count);
        }

        String stat = RenderUtil.render(new LabelElement(threadStat), process.width());
        String content = RenderUtil.render(threads.values().iterator(),
                new ThreadRenderer(sampleInterval), process.width());
        process.write(stat + content);
    }

    private void processBlockingThread(CommandProcess process) {
        ThreadUtil.BlockingLockInfo blockingLockInfo = ThreadUtil.findMostBlockingLock();

        if (blockingLockInfo.threadInfo == null) {
            process.write("No most blocking thread found!\n");
        } else {
            String stacktrace = ThreadUtil.getFullStacktrace(blockingLockInfo);
            process.write(stacktrace);
        }
    }

    private void processTopBusyThreads(CommandProcess process) {
        Map<Long, Long> topNThreads = ThreadUtil.getTopNThreads(sampleInterval, topNBusy);
        Long[] tids = topNThreads.keySet().toArray(new Long[0]);
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(ArrayUtils.toPrimitive(tids), true, true);
        if (threadInfos == null) {
            process.write("thread do not exist! id: " + id + "\n");
        } else {
            for (ThreadInfo info : threadInfos) {
                String stacktrace = ThreadUtil.getFullStacktrace(info, topNThreads.get(info.getThreadId()));
                process.write(stacktrace + "\n");
            }
        }
    }

    private void processThread(CommandProcess process) {
        String content;
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(new long[]{id}, true, true);
        if (threadInfos == null || threadInfos[0] == null) {
            content = "thread do not exist! id: " + id + "\n";
        } else {
            // no cpu usage info
            content = ThreadUtil.getFullStacktrace(threadInfos[0], -1);
        }
        process.write(content);
    }
}
