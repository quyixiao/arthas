package com.taobao.arthas.core.shell.handlers.shell;

import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.cli.CliTokens;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.impl.ShellImpl;
import com.taobao.arthas.core.shell.system.ExecStatus;
import com.taobao.arthas.core.shell.system.Job;
import com.taobao.arthas.core.shell.term.Term;
import com.taobao.arthas.core.util.TokenUtils;

import java.util.List;

/**
 * @author beiwei30 on 23/11/2016.
 */
public class ShellLineHandler implements Handler<String> {

    private ShellImpl shell;
    private Term term;

    public ShellLineHandler(ShellImpl shell) {
        this.shell = shell;
        this.term = shell.term();
    }

    // 下面我们输入 help 来看下项目整个过程
    // help 输入来到上面的readline方法，最终回调到 ShellLineHandler.handle方法，ShellLineHandler handle 方法关键步骤处理如下
    // ShellLineHandler 的 handle 方法会根据不同的请求命令执行不同的逻辑
    // 1. 如果是 exit,logout,quit,jobs,fg,bg,kill
    // 2.如果是其他的命令，则创建Job ,并运行，创建 job 的类图如下所示
    // 比较多
    // 创建 job 时，会根据具体的客户端传递的命令，找到对应的Command ，并包装成 Process,Process 再被包装成 Job
    // 运行 Job 时，反向先调用，再找到对应的 Command,最终调用 Command的 process 处理请求
    //
    @Override
    public void handle(String line) {
        if (line == null) {
            // EOF
            handleExit();
            return;
        }

        List<CliToken> tokens = CliTokens.tokenize(line);
        CliToken first = TokenUtils.findFirstTextToken(tokens);
        if (first == null) {
            // For now do like this
            shell.readline();
            return;
        }
        // 如果是exit，logout,quit,jobs,fg,bg,kill等直接执行退出
        String name = first.value();
        if (name.equals("exit") || name.equals("logout") || name.equals("quit")) {
            handleExit();
            return;
        } else if (name.equals("jobs")) {
            handleJobs();
            return;
        } else if (name.equals("fg")) {
            handleForeground(tokens);
            return;
        } else if (name.equals("bg")) {
            handleBackground(tokens);
            return;
        } else if (name.equals("kill")) {
            handleKill(tokens);
            return;
        }
        // 如果是其他的命令，则创建Job，并运行，创建job的类图如下
        // 创建job时，会根据具体的客户端命令，找到对应的Commnad，并包装成Process,Process再被包装成job
        // 运行job时，反射先调用process，再找到对应的Command，最终调用Command的process处理请求
        // Commnad主要分为两类
        // 1.不需要使用字节码增强命令，其中的JVM相关的使用java.lang.management提供了管理接口，来查看具体的运行时数据，比较简单了，就不
        // 介绍了
        // 2. 需要使用字节码增强命令
        // 3. 需要使用字节码增强,字节码增加了统一的继承 EnhancerCommand 类，process方法里面调用了enhance方法进行增强，调用了enhance方法
        // 该方法的内部调用了inst.addTransformer方法添加自定义的ClassFileTransformer，这边是Enhancer类
        // Enhancer类使用了AdviceWeaver继承ClassVisitor,用来修改类的字节码，重写了VisiMethod方法，在该方法里面修改类指定的方法，
        // visitMethod方法里面使用了AdviceAdapter继承了MethodVisitor类，在onMethodEnter方法，onMethodExit方法中，把Spy类对应的方法
        // (ON_BEFORE_METHOD,ON_RETURN_METHON，ON_THROWS_METHOD等)编织到目标类的方法对应的位置
        //
        // 在前面的Spy初始化的时候可以看到，这几个方法其实指向的是AdviceWeaver类的methodBegin，methodOnReturnEnd等，在这些方法里面
        // 对应的AdviceListener，并调用AdviceListener的对应的方法，比如 before，afterReturning,AfterThrowing
        // 通过这几种方式，可以实现不同的Command使用不同的AdviceListener，从而实现不同的处理逻辑下面找几个常用的AdviceListener介绍下
        //下面找几个常用的AdviceListener介绍下
        // 1.StackAdviceListener
        // 在方法执行前，记录规模和方法的耗时
        // 2.WatchAdviceListener
        // 满足条件时打印参数或者结果，条件表达式使用Ognl语法
        // 3.TraceAdviceListener
        // 在每个方法前后都记录，并维护一个调用树结构
        // 客户端代码在arthas-client模式里，入口类是com.taobao.arthas.client.telnetConsole主要使用apache common-net jar 进行telnet
        // 连接，关键的代码有下面几个步骤
        // 1.构造TelnetClient对象，并初始化
        // 2.构造ConsoleReader对象，并初始化
        // 3.调用IOUtil.readWrite(telnet.getInputStream(),telnet.getOutputStream(),System.in,consoleReader.getOutput())处理各个
        // 流，一共有四个流
        // telnet.getInputStream()
        // telnet.getOutputStream()
        // System.in
        // consoleReader.getOutput()
        // 请求时，从本地System.in读取，发送到telnet.getOutputStream()，即发送给远程服务器，响应时从telnet.InputStream()读取远程
        // 服务发送过来响应，并传递给consoleReader.getOutput()，并传递给consoleReader.getOutput()，即本地控制台输出
        // 这里做了前置的文件检查以及解析，help 命令顺利到了createJob 这一步，一层层的封装进去，这里主要的遍历前面加载到内存命令，如果找
        //不到，command not found
        // 然后同时创建实例化CommandProcess,这里注意的是找到 command对应的 processHandler赋值给 ProcessImpl 属性了，这里就埋下伏笔，
        // 为后面路由找到 helpCommand
        //

        Job job = createJob(tokens);
        if (job != null) {
            // 然后创建完 job，继续回到 ShellLineHandler Job 的那块代码，上面的代码可以看出 job.run(),对 job 启动，这里比较重要的是
            // 刚才创建 Process对象，这里调用的是 run 方法
            job.run();
        }
    }

    private int getJobId(String arg) {
        int result = -1;
        try {
            if (arg.startsWith("%")) {
                result = Integer.parseInt(arg.substring(1));
            } else {
                result = Integer.parseInt(arg);
            }
        } catch (Exception e) {
        }
        return result;
    }

    private Job createJob(List<CliToken> tokens) {
        Job job;
        try {
            job = shell.createJob(tokens);
        } catch (Exception e) {
            term.echo(e.getMessage() + "\n");
            shell.readline();
            return null;
        }
        return job;
    }

    private void handleKill(List<CliToken> tokens) {
        String arg = TokenUtils.findSecondTokenText(tokens);
        if (arg == null) {
            term.write("kill: usage: kill job_id\n");
            shell.readline();
            return;
        }
        Job job = shell.jobController().getJob(getJobId(arg));
        if (job == null) {
            term.write(arg + " : no such job\n");
            shell.readline();
        } else {
            job.terminate();
            term.write("kill job " + job.id() + " success\n");
            shell.readline();
        }
    }

    private void handleBackground(List<CliToken> tokens) {
        String arg = TokenUtils.findSecondTokenText(tokens);
        Job job;
        if (arg == null) {
            job = shell.getForegroundJob();
        } else {
            job = shell.jobController().getJob(getJobId(arg));
        }
        if (job == null) {
            term.write(arg + " : no such job\n");
            shell.readline();
        } else {
            if (job.status() == ExecStatus.STOPPED) {
                job.resume(false);
                term.echo(shell.statusLine(job, ExecStatus.RUNNING));
                shell.readline();
            } else {
                term.write("job " + job.id() + " is already running\n");
                shell.readline();
            }
        }
    }

    private void handleForeground(List<CliToken> tokens) {
        String arg = TokenUtils.findSecondTokenText(tokens);
        Job job;
        if (arg == null) {
            job = shell.getForegroundJob();
        } else {
            job = shell.jobController().getJob(getJobId(arg));
        }
        if (job == null) {
            term.write(arg + " : no such job\n");
            shell.readline();
        } else {
            if (job.getSession() != shell.session()) {
                term.write("job " + job.id() + " doesn't belong to this session, so can not fg it\n");
                shell.readline();
            } else if (job.status() == ExecStatus.STOPPED) {
                job.resume(true);
            } else if (job.status() == ExecStatus.RUNNING) {
                // job is running
                job.toForeground();
            } else {
                term.write("job " + job.id() + " is already terminated, so can not fg it\n");
                shell.readline();
            }
        }
    }

    private void handleJobs() {
        for (Job job : shell.jobController().jobs()) {
            String statusLine = shell.statusLine(job, job.status());
            term.write(statusLine);
        }
        shell.readline();
    }

    private void handleExit() {
        term.close();
    }
}
