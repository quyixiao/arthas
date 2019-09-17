package com.taobao.arthas.core.command.monitor200;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.Collections;
import java.util.List;

import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.advisor.InvokeTraceable;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.handlers.command.CommandInterruptHandler;
import com.taobao.arthas.core.shell.handlers.shell.QExitHandler;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.middleware.logger.Logger;

/**
 * @author beiwei30 on 29/11/2016.
 */
public abstract class EnhancerCommand extends AnnotatedCommand {

    private static final Logger logger = LogUtil.getArthasLogger();
    protected static final List<String> EMPTY = Collections.emptyList();
    public static final String[] EXPRESS_EXAMPLES = { "params", "returnObj", "throwExp", "target", "clazz", "method",
                                                       "{params,returnObj}", "params[0]" };

    protected Matcher classNameMatcher;
    protected Matcher methodNameMatcher;

    /**
     * 类名匹配
     *
     * @return 获取类名匹配
     */
    protected abstract Matcher getClassNameMatcher();

    /**
     * 方法名匹配
     *
     * @return 获取方法名匹配
     */
    protected abstract Matcher getMethodNameMatcher();

    /**
     * 获取监听器
     *
     * @return 返回监听器
     */
    protected abstract AdviceListener getAdviceListener(CommandProcess process);

    @Override
    public void process(final CommandProcess process) {
        // ctrl-C support
        process.interruptHandler(new CommandInterruptHandler(process));
        // q exit support
        process.stdinHandler(new QExitHandler(process));

        // start to enhance
        // 字节码增强的命令统一继承EnhancerCommand类，process 方法里面调用 enhance 方法进行增强，调用 Enhancer 类 enhance方法，
        // 该方法的内部调用 inst.AddTransformer 方法添加自定义的 ClassFileTransfermer，这边是 Enhancer类
        // Enhancer 类使用 AdviceWeaver（继承 ClassVisitor）,用来修改类的字节码，重写了 visitMethod方法，访方法里面修改指定的方法
        // visitMethod方法使用了 AdviceAdapter（继承了MethodVisitor 类），在 onMethodEnter方法，onMethodExit 方法中，把 Spy 类对应的方法
        // (ON_BEFORE_METHON,ON_RETURN_METHON,ON_THROWS_METHOD 等)编织到目标类的方法对应的位置
        // 在前面的 Spy初始化的时候可以看到，这几个方法其实指向的是 AdviceWeaver 类的 methodOnBegin,MethodOnReturnEnd 等，这些方法
        // 里都会根据 AdviceId 查找到对应的 AdviceListener，并调用 AdviceListener 对应的方法，比如 before ，afterReturning,afterThrowing
        // 通过这种方式，可以实现不同的 Command 使用不同的 AdviceListener，从而实现不同的处理逻辑
        // 下面找几个常用的 AdviceListener 介绍下
        // StackAdviceListener
        // 方法执行前，记录堆栈和方法耗时
        // WatchAdviceListener
        // 满足条件时打印参数或者结果，条件表达式使用 Ognl语法
        // TraceAdviceListener
        // 在每个方法前后都记录，并维护了一个调用树结构

        enhance(process);
    }

    @Override
    public void complete(Completion completion) {
        int argumentIndex = CompletionUtils.detectArgumentIndex(completion);

        if (argumentIndex == 1) { // class name
            if (!CompletionUtils.completeClassName(completion)) {
                super.complete(completion);
            }
            return;
        } else if (argumentIndex == 2) { // method name
            if (!CompletionUtils.completeMethodName(completion)) {
                super.complete(completion);
            }
            return;
        } else if (argumentIndex == 3) { // watch express
            completeArgument3(completion);
            return;
        }

        super.complete(completion);
    }

    protected void enhance(CommandProcess process) {
        Session session = process.session();
        if (!session.tryLock()) {
            process.write("someone else is enhancing classes, pls. wait.\n");
            process.end();
            return;
        }
        int lock = session.getLock();
        try {
            Instrumentation inst = session.getInstrumentation();
            AdviceListener listener = getAdviceListener(process);
            if (listener == null) {
                warn(process, "advice listener is null");
                return;
            }
            boolean skipJDKTrace = false;
            if(listener instanceof AbstractTraceAdviceListener) {
                skipJDKTrace = ((AbstractTraceAdviceListener) listener).getCommand().isSkipJDKTrace();
            }

            EnhancerAffect effect = Enhancer.enhance(inst, lock, listener instanceof InvokeTraceable,
                    skipJDKTrace, getClassNameMatcher(), getMethodNameMatcher());

            if (effect.cCnt() == 0 || effect.mCnt() == 0) {
                // no class effected
                // might be method code too large
                process.write("No class or method is affected, try:\n"
                              + "1. sm CLASS_NAME METHOD_NAME to make sure the method you are tracing actually exists (it might be in your parent class).\n"
                              + "2. reset CLASS_NAME and try again, your method body might be too large.\n"
                              + "3. check arthas log: " + LogUtil.LOGGER_FILE + "\n"
                              + "4. visit https://github.com/alibaba/arthas/issues/47 for more details.\n");
                process.end();
                return;
            }

            // 这里做个补偿,如果在enhance期间,unLock被调用了,则补偿性放弃
            if (session.getLock() == lock) {
                // 注册通知监听器
                process.register(lock, listener);
                if (process.isForeground()) {
                    process.echoTips(Constants.Q_OR_CTRL_C_ABORT_MSG + "\n");
                }
            }

            process.write(effect + "\n");
        } catch (UnmodifiableClassException e) {
            logger.error(null, "error happens when enhancing class", e);
        } finally {
            if (session.getLock() == lock) {
                // enhance结束后解锁
                process.session().unLock();
            }
        }
    }

    protected void completeArgument3(Completion completion) {
        super.complete(completion);
    }

    private static void warn(CommandProcess process, String message) {
        logger.error(null, message);
        process.write("cannot operate the current command, pls. check arthas.log\n");
        if (process.isForeground()) {
            process.echoTips(Constants.Q_OR_CTRL_C_ABORT_MSG + "\n");
        }
    }

}
