package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.express.ExpressFactory;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.handlers.command.CommandInterruptHandler;
import com.taobao.arthas.core.shell.handlers.shell.QExitHandler;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.affect.RowAffect;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.arthas.core.view.ObjectView;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.logger.Logger;
import com.taobao.text.ui.TableElement;
import com.taobao.text.util.RenderUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Integer.toHexString;
import static java.lang.String.format;

/**
 * 时光隧道命令<br/>
 * 参数w/d依赖于参数i所传递的记录编号<br/>
 *
 * 方法执行数据的时空隧道，记录下指定方法每次调用的入参和返回信息，并能对这些不同的时间下调用进行观测
 * watch 虽然很方便和灵活，但需要提前想清楚观察表达式的拼写，这对排查问题而应该要示太高，因为很多的时候
 * 我们并不清楚问题出自于何方，只能靠蛛丝马迹进行猜测
 *
 * 这个时候如果能记录下当时谢谢老婆调用所有入参返回值，抛出的异常会对整个问题思考与判断非常有帮助
 * 于是科，TimeTunnel命令就诞生了
 *
 *  使用参考
 *  启动Demo
 *  启动快速入门里的arthas-demo
 *  记录调用
 *  对于一个最基本的使用来说，就是记录下当前方法的每次调用环境现场
 *  tt -t demo.MathGame primeFactors
 *  命令参数解析
 *  tt命令有很多的主要参数，-t就是其中之一，这个参数的表明希望记录下类*Test的print方法每次执行情况
 *  -n 3
 *  当你执行了一个调用量不高的方法时可能你不能有足够的时间用CTRL+C中断tt命令记录的过程，但是如果遇到调用量，瞬间能将你的JVM内存撑爆
 *  此时你可以通过-n参数指定你记录的次数，当达到记录次数时Arthas 会主动中断tt命令记录的过程，避免人工挫伤无法停止的情况
 *
 *
 *  不知道大家是否在使用的过程中遇到以下困惑
 *  Arthas似乎很难区分重载方法
 *  我只需要观察特定的参数，但是tt却全部都给我记录下来
 *  条件表达式也用OGNL来编写，核心判断骑大象依然是Advice对象，除了tt命令之外 ，watch ,trace，stack命令也都支持条件表达式
 *  解决方法的重载
 *  tt -t *Test print params.length==1
 *  通过制定参数的形式解决不同方法的签名，如果参数个数不一样，你还可以这样写
 *  tt -t *Test print 'params[1] instanceof Integer'
 *  解决指定参数
 *  tt -t *Test print params[0].mobile=='13989838402'
 *  构成条件表达式的Advice对象
 *  前边条件表达式的Advice对象
 *  前边很多的条件表达式中，都使用了params[0],有关这个变量的介绍，请参考表达式核心变量
 *  当你用tt记录了一大片的时间片段之后，你希望从中筛选出自己需要的时间片段， 这个时候你就需要对现有的记录进行检索了
 *  tt -l
 *  我需要筛选出primeFactors 方法的调用信息
 *  tt -s 'method.name=="primeFactors"'
 *  你需要一个-s的参数，同样的，搜索表达式的核心对象依旧是Advice对象
 *  对于一个具体的时间处信息而言，你可以通过-i参数后边跟着对应的INDEX编号查看到他的详细信息
 *  tt -i 1003
 *  tt命令由于保存了当时调用的所有的现场信息，所以我们可以自己主动的对一个INDEX编号的时间自主的发起一次调用，从而
 *  解放你的沟通成本，此时你需要一个-p参数，通过--repay-times指定调用的次数，通过--replay-interval指定多次调用的时间间隔（单位ms ,
 *  默认是1000ms）
 *
 *  tt -i 1004 -p
 *
 *  你会发现结果虽然一样了，但是调用的路径发生的变化，有了原来的程序发起的变成了arthas自己内部线程发起的调用了
 *  需要强调的点
 *  ThreadLocal信息丢失有
 *  很多的框架偷偷的将一些环境变量信息塞到了发起调用的线程的ThreadLocal中，由于调用的线程发生的变化，这些ThreadLocal线程信息无法通过Arthas保存，
 *  所以这些信息将会丢失
 *  一些常见的Case 比如鹰眼TraceId
 *  2.需要强调的的是，命令是将当前环境的对象引用保存起来，但仅仅能保存一个引用布局，如果方法内部对参数进行了变更，或者返回的对象已经过了
 *  后续的处理，那么在tt查看到的时候将无法看到当前最准确的值，这也是为什么watch命令存在的意义
 *
 *
 *
 *
 *  
 *
 *
 * @author vlinux on 14/11/15.
 */
@Name("tt")
@Summary("Time Tunnel")
@Description(Constants.EXPRESS_DESCRIPTION + Constants.EXAMPLE +
        "  tt -t *StringUtils isEmpty\n" +
        "  tt -t *StringUtils isEmpty params[0].length==1\n" +
        "  tt -l\n" +
        "  tt -i 1000\n" +
        "  tt -i 1000 -w params[0]\n" +
        "  tt -i 1000 -p \n" +
        "  tt -i 1000 -p --replay-times 3 --replay-interval 3000\n" +
        "  tt --delete-all\n" +
        Constants.WIKI + Constants.WIKI_HOME + "tt")
public class TimeTunnelCommand extends EnhancerCommand {
    // 时间隧道(时间碎片的集合)
    private static final Map<Integer, TimeFragment> timeFragmentMap = new LinkedHashMap<Integer, TimeFragment>();
    // 时间碎片序列生成器
    private static final AtomicInteger sequence = new AtomicInteger(1000);
    // TimeTunnel the method call
    private boolean isTimeTunnel = false;
    private String classPattern;
    private String methodPattern;
    private String conditionExpress;
    // list the TimeTunnel
    private boolean isList = false;
    private boolean isDeleteAll = false;
    // index of TimeTunnel //INDEX 时间片段编号 ，每一个编号
    //编号代表着一次调用，后续tt还有很多的命令都基于编号指定记录操作，非常重要
    private Integer index;
    // expand of TimeTunnel
    private Integer expand = 1;
    // upper size limit
    private Integer sizeLimit = 10 * 1024 * 1024;
    // watch the index TimeTunnel
    private String watchExpress = com.taobao.arthas.core.util.Constants.EMPTY_STRING;
    private String searchExpress = com.taobao.arthas.core.util.Constants.EMPTY_STRING;
    // play the index TimeTunnel
    private boolean isPlay = false;
    // delete the index TimeTunnel
    private boolean isDelete = false;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private int replayTimes = 1;
    private long replayInterval = 1000L;
    private static final Logger logger = LogUtil.getArthasLogger();

    @Argument(index = 0, argName = "class-pattern", required = false)
    @Description("Path and classname of Pattern Matching")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    //方法执行的本机时间，记录了这个时间片段所发生的本机时间
    @Argument(index = 1, argName = "method-pattern", required = false)
    @Description("Method of Pattern Matching")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    //方法的执行耗时
    @Argument(index = 2, argName = "condition-express", required = false)
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    //
    @Option(shortName = "t", longName = "time-tunnel", flag = true)
    @Description("Record the method invocation within time fragments")
    public void setTimeTunnel(boolean timeTunnel) {
        isTimeTunnel = timeTunnel;
    }

    @Option(shortName = "l", longName = "list", flag = true)
    @Description("List all the time fragments")
    public void setList(boolean list) {
        isList = list;
    }

    @Option(longName = "delete-all", flag = true)
    @Description("Delete all the time fragments")
    public void setDeleteAll(boolean deleteAll) {
        isDeleteAll = deleteAll;
    }

    @Option(shortName = "i", longName = "index")
    @Description("Display the detailed information from specified time fragment")
    public void setIndex(Integer index) {
        this.index = index;
    }

    @Option(shortName = "x", longName = "expand")
    @Description("Expand level of object (1 by default)")
    public void setExpand(Integer expand) {
        this.expand = expand;
    }

    @Option(shortName = "M", longName = "sizeLimit")
    @Description("Upper size limit in bytes for the result (10 * 1024 * 1024 by default)")
    public void setSizeLimit(Integer sizeLimit) {
        this.sizeLimit = sizeLimit;
    }

    @Option(shortName = "w", longName = "watch-express")
    @Description(value = "watch the time fragment by ognl express.\n" + Constants.EXPRESS_EXAMPLES)
    public void setWatchExpress(String watchExpress) {
        this.watchExpress = watchExpress;
    }

    @Option(shortName = "s", longName = "search-express")
    @Description("Search-expression, to search the time fragments by ognl express.\n" +
            "The structure of 'advice' like conditional expression")
    public void setSearchExpress(String searchExpress) {
        this.searchExpress = searchExpress;
    }

    @Option(shortName = "p", longName = "play", flag = true)
    @Description("Replay the time fragment specified by index")
    public void setPlay(boolean play) {
        isPlay = play;
    }

    @Option(shortName = "d", longName = "delete", flag = true)
    @Description("Delete time fragment specified by index")
    public void setDelete(boolean delete) {
        isDelete = delete;
    }

    @Option(shortName = "E", longName = "regex", flag = true)
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")
    @Description("Threshold of execution times")
    public void setNumberOfLimit(int numberOfLimit) {
        this.numberOfLimit = numberOfLimit;
    }


    @Option(longName = "replay-times")
    @Description("execution times when play tt")
    public void setReplayTimes(int replayTimes) {
        this.replayTimes = replayTimes;
    }

    @Option(longName = "replay-interval")
    @Description("replay interval  for  play tt with option r greater than 1")
    public void setReplayInterval(int replayInterval) {
        this.replayInterval = replayInterval;
    }


    public boolean isRegEx() {
        return isRegEx;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public String getClassPattern() {
        return classPattern;
    }

    public String getConditionExpress() {
        return conditionExpress;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }


    public int getReplayTimes() {
        return replayTimes;
    }

    public long getReplayInterval() {
        return replayInterval;
    }

    private boolean hasWatchExpress() {
        return !StringUtils.isEmpty(watchExpress);
    }

    private boolean hasSearchExpress() {
        return !StringUtils.isEmpty(searchExpress);
    }

    private boolean isNeedExpand() {
        return null != expand && expand > 0;
    }

    /**
     * 检查参数是否合法
     */
    private void checkArguments() {
        // 检查d/p参数是否有i参数配套
        if ((isDelete || isPlay) && null == index) {
            throw new IllegalArgumentException("Time fragment index is expected, please type -i to specify");
        }

        // 在t参数下class-pattern,method-pattern
        if (isTimeTunnel) {
            if (StringUtils.isEmpty(classPattern)) {
                throw new IllegalArgumentException("Class-pattern is expected, please type the wildcard expression to match");
            }
            if (StringUtils.isEmpty(methodPattern)) {
                throw new IllegalArgumentException("Method-pattern is expected, please type the wildcard expression to match");
            }
        }

        // 一个参数都没有是不行滴
        if (null == index && !isTimeTunnel && !isDeleteAll && StringUtils.isEmpty(watchExpress)
                && !isList && StringUtils.isEmpty(searchExpress)) {
            throw new IllegalArgumentException("Argument(s) is/are expected, type 'help tt' to read usage");
        }
    }

    /*
     * 记录时间片段
     */
    int putTimeTunnel(TimeFragment tt) {
        int indexOfSeq = sequence.getAndIncrement();
        timeFragmentMap.put(indexOfSeq, tt);
        return indexOfSeq;
    }

    @Override
    public void process(final CommandProcess process) {
        // 检查参数
        checkArguments();

        // ctrl-C support
        process.interruptHandler(new CommandInterruptHandler(process));
        // q exit support
        process.stdinHandler(new QExitHandler(process));

        if (isTimeTunnel) {
            enhance(process);
        } else if (isPlay) {
            processPlay(process);
        } else if (isList) {
            processList(process);
        } else if (isDeleteAll) {
            processDeleteAll(process);
        } else if (isDelete) {
            processDelete(process);
        } else if (hasSearchExpress()) {
            processSearch(process);
        } else if (index != null) {
            if (hasWatchExpress()) {
                processWatch(process);
            } else {
                processShow(process);
            }
        }
    }

    @Override
    protected Matcher getClassNameMatcher() {
        if (classNameMatcher == null) {
            classNameMatcher = SearchUtils.classNameMatcher(getClassPattern(), isRegEx());
        }
        return classNameMatcher;
    }

    @Override
    protected Matcher getMethodNameMatcher() {
        if (methodNameMatcher == null) {
            methodNameMatcher = SearchUtils.classNameMatcher(getMethodPattern(), isRegEx());
        }
        return methodNameMatcher;
    }

    @Override
    protected AdviceListener getAdviceListener(CommandProcess process) {
        return new TimeTunnelAdviceListener(this, process);
    }

    // 展示指定记录
    private void processShow(CommandProcess process) {
        RowAffect affect = new RowAffect();
        try {
            TimeFragment tf = timeFragmentMap.get(index);
            if (null == tf) {
                process.write(format("Time fragment[%d] does not exist.", index)).write("\n");
                return;
            }

            Advice advice = tf.getAdvice();
            String className = advice.getClazz().getName();
            String methodName = advice.getMethod().getName();
            String objectAddress = advice.getTarget() == null ? "NULL" : "0x" + toHexString(advice.getTarget().hashCode());

            TableElement table = TimeTunnelTable.createDefaultTable();
            TimeTunnelTable.drawTimeTunnel(tf, index, table);
            TimeTunnelTable.drawMethod(advice, className, methodName, objectAddress, table);
            TimeTunnelTable.drawParameters(advice, table, isNeedExpand(), expand);
            TimeTunnelTable.drawReturnObj(advice, table, isNeedExpand(), expand, sizeLimit);
            TimeTunnelTable.drawThrowException(advice, table, isNeedExpand(), expand);

            process.write(RenderUtil.render(table, process.width()));
            affect.rCnt(1);
        } finally {
            process.write(affect.toString()).write("\n");
            process.end();
        }
    }

    // 查看记录信息
    private void processWatch(CommandProcess process) {
        RowAffect affect = new RowAffect();
        try {
            final TimeFragment tf = timeFragmentMap.get(index);
            if (null == tf) {
                process.write(format("Time fragment[%d] does not exist.", index)).write("\n");
                return;
            }

            Advice advice = tf.getAdvice();
            Object value = ExpressFactory.threadLocalExpress(advice).get(watchExpress);
            if (isNeedExpand()) {
                process.write(new ObjectView(value, expand, sizeLimit).draw()).write("\n");
            } else {
                process.write(StringUtils.objectToString(value)).write("\n");
            }

            affect.rCnt(1);
        } catch (ExpressException e) {
            logger.warn("tt failed.", e);
            process.write(e.getMessage() + ", visit " + LogUtil.LOGGER_FILE + " for more detail\n");
        } finally {
            process.write(affect.toString()).write("\n");
            process.end();
        }
    }

    // do search timeFragmentMap
    private void processSearch(CommandProcess process) {
        RowAffect affect = new RowAffect();
        try {
            // 匹配的时间片段
            Map<Integer, TimeFragment> matchingTimeSegmentMap = new LinkedHashMap<Integer, TimeFragment>();
            for (Map.Entry<Integer, TimeFragment> entry : timeFragmentMap.entrySet()) {
                int index = entry.getKey();
                TimeFragment tf = entry.getValue();
                Advice advice = tf.getAdvice();

                // 搜索出匹配的时间片段
                if ((ExpressFactory.threadLocalExpress(advice)).is(searchExpress)) {
                    matchingTimeSegmentMap.put(index, tf);
                }
            }

            if (hasWatchExpress()) {
                // 执行watchExpress
                TableElement table = TimeTunnelTable.createDefaultTable();
                TimeTunnelTable.drawWatchTableHeader(table);
                TimeTunnelTable.drawWatchExpress(matchingTimeSegmentMap, table, watchExpress, isNeedExpand(), expand, sizeLimit);
                process.write(RenderUtil.render(table, process.width()));
            } else {
                // 单纯的列表格
                process.write(RenderUtil.render(TimeTunnelTable.drawTimeTunnelTable(matchingTimeSegmentMap), process.width()));
            }

            affect.rCnt(matchingTimeSegmentMap.size());
        } catch (ExpressException e) {
            LogUtil.getArthasLogger().warn("tt failed.", e);
            process.write(e.getMessage() + ", visit " + LogUtil.LOGGER_FILE + " for more detail\n");
        } finally {
            process.write(affect.toString()).write("\n");
            process.end();
        }
    }

    // 删除指定记录
    private void processDelete(CommandProcess process) {
        RowAffect affect = new RowAffect();
        if (timeFragmentMap.remove(index) != null) {
            affect.rCnt(1);
        }
        process.write(format("Time fragment[%d] successfully deleted.", index)).write("\n");
        process.write(affect.toString()).write("\n");
        process.end();
    }

    private void processDeleteAll(CommandProcess process) {
        int count = timeFragmentMap.size();
        RowAffect affect = new RowAffect(count);
        timeFragmentMap.clear();
        process.write("Time fragments are cleaned.\n");
        process.write(affect.toString()).write("\n");
        process.end();
    }

    private void processList(CommandProcess process) {
        RowAffect affect = new RowAffect();
        process.write(RenderUtil.render(TimeTunnelTable.drawTimeTunnelTable(timeFragmentMap), process.width()));
        affect.rCnt(timeFragmentMap.size());
        process.write(affect.toString()).write("\n");
        process.end();
    }

    /**
     * 重放指定记录
     */
    private void processPlay(CommandProcess process) {
        TimeFragment tf = timeFragmentMap.get(index);
        if (null == tf) {
            process.write(format("Time fragment[%d] does not exist.", index) + "\n");
            process.end();
            return;
        }
        Advice advice = tf.getAdvice();
        String className = advice.getClazz().getName();
        String methodName = advice.getMethod().getName();
        String objectAddress = advice.getTarget() == null ? "NULL" : "0x" + toHexString(advice.getTarget().hashCode());
        ArthasMethod method = advice.getMethod();
        boolean accessible = advice.getMethod().isAccessible();
        try {
            if (!accessible) {
                method.setAccessible(true);
            }
            for (int i = 0; i < getReplayTimes(); i++) {
                if (i > 0) {
                    //wait for the next execution
                    Thread.sleep(getReplayInterval());
                    if (!process.isRunning()) {
                        return;
                    }
                }
                long beginTime = System.nanoTime();
                TableElement table = TimeTunnelTable.createDefaultTable();
                if (i != 0) {
                    // empty line separator
                    process.write("\n");
                }
                TimeTunnelTable.drawPlayHeader(className, methodName, objectAddress, index, table);
                TimeTunnelTable.drawParameters(advice, table, isNeedExpand(), expand);

                try {
                    Object returnObj = method.invoke(advice.getTarget(), advice.getParams());
                    double cost = (System.nanoTime() - beginTime) / 1000000.0;
                    TimeTunnelTable.drawPlayResult(table, returnObj, isNeedExpand(), expand, sizeLimit, cost);
                } catch (Throwable t) {
                    TimeTunnelTable.drawPlayException(table, t, isNeedExpand(), expand);
                }
                process.write(RenderUtil.render(table, process.width()))
                        .write(format("Time fragment[%d] successfully replayed %d times.", index, i+1))
                        .write("\n");
            }
        } catch (Throwable t) {
            logger.warn("tt replay failed.", t);
        } finally {
            method.setAccessible(accessible);
            process.end();
        }
    }
}
