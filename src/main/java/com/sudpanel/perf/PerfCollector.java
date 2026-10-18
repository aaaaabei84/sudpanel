package com.sudpanel.perf;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.sudpanel.adb.AdbClient;

/**
 * 性能采样器。每个采样点用一条 adb shell 同时刻取齐：PSS/RSS（dumpsys meminfo）、
 * CPU%（top）、线程总数（/proc/pid/status）、WebSocket 线程数（/proc/pid/task/*&#47;comm）。
 *
 * 兼容的 Android 输出格式（以下为合成示例）：
 * - meminfo 行：`TOTAL PSS:   1000            TOTAL RSS:   2000 ...`
 * - status 行：`VmRSS:\t  2000 kB`、`Threads:\t10`
 * - top 行：PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS（%CPU 为第 9 列）
 * - comm 线程名截断 15 字符；ws/网络线程按名称包含 websocket/okhttp/okio（忽略大小写）过滤
 *
 * meminfo 的 PSS 与 RSS 可能位于同一行，因此在 PSS 分支内继续匹配 RSS，
 * 避免只保留 /proc/pid/status 的 VmRSS 兜底值。线程名按换行拆分。
 *
 * 口径说明（2026-09-16 修订）：不同游戏引擎/SDK 的 WebSocket 实现线程命名差异很大——
 * 原生实现每连接一线程（线程名 WebSocket 前缀）；OkHttp 系实现复用线程池（线程名 OkHttp
 * TaskRunner/Okio Watchdog，连接数变化不改变线程数）。因此本指标实际观测的是「网络 IO
 * 线程」，仅能证明存在网络线程，不能精确证明 ws 连接数；对 OkHttp 系游戏该指标判定意义有限。
 */
@Service
public class PerfCollector {

    // 匹配 dumpsys meminfo 输出中的 TOTAL PSS 行，捕获 PSS 数值（单位 kB）
    // 部分 ROM/老版本输出 "TOTAL:" 而非 "TOTAL PSS:"，两种格式都要兼容。
    // 两种都认；解析时 PSS 优先 TOTAL PSS，缺失时回退 TOTAL:
    private static final Pattern PSS = Pattern.compile("TOTAL PSS:\\s+(\\d+)");
    private static final Pattern PSS_TOTAL = Pattern.compile("^TOTAL:\\s+(\\d+)");
    // 匹配 dumpsys meminfo 输出中的 TOTAL RSS 行（meminfo 口径，优先使用）
    private static final Pattern MEMINFO_RSS = Pattern.compile("TOTAL RSS:\\s+(\\d+)");
    // 匹配 /proc/<pid>/status 中的 VmRSS 行（meminfo 拿不到 RSS 时的兜底口径）
    private static final Pattern VM_RSS = Pattern.compile("VmRSS:\\s+(\\d+)");
    // 匹配 /proc/<pid>/status 中的 Threads 行（进程总线程数）
    private static final Pattern THREADS = Pattern.compile("Threads:\\s+(\\d+)");
    // ws/网络线程判定规则：线程名包含 websocket/okhttp/okio（(?i) 忽略大小写）
    private static final Pattern WS_NAME = Pattern.compile("(?i).*(websocket|okhttp|okio).*");
    // /proc/<pid>/status 的 Uid 行：拿 app 的 uid（用于 /proc/net/tcp 按 uid 过滤出该 app 的连接）
    private static final Pattern UID_LINE = Pattern.compile("^Uid:\\s+(\\d+)");
    // dumpsys meminfo 的 "App Summary" 分项行（2026-09-28 新增，对应 v1.2 #14）。
    // 合成格式示例（App Summary 段，共 Pss(KB) / Rss(KB) 两列）：
    //   "           Java Heap:     1000                           2000"
    //   "         Native Heap:     3000                           4000"
    //   "       Private Other:     1000"      ← 该行只有 Pss，无 Rss
    //   "             Unknown:                                   1000"   ← 该行只有 Rss，Pss 为空
    // 规则：标签后**第一个数字**即 Pss(KB)；只认带冒号的标签，避免误命中上方明细表的同名行
    // （明细表里的同名标签没有冒号；图形部分也可能使用 "GL mtrack" 标签）。
    private static final Pattern SUMMARY_ITEM = Pattern.compile(
            "^\\s*(Java Heap|Native Heap|Code|Stack|Graphics|Private Other|System):\\s+(\\d+)");
    // /sys/devices/system/cpu/online 的行格式（2026-10-03 新增，v1.5 #21）：
    //   "0-7"（8 核）/"0-3,5-7"（大小核热插拔，4+3=7 核）/"0"（1 核）
    // 整行匹配「数字段 + 可选连字符区间 + 逗号分隔」，避免误命中其它命令输出行
    private static final Pattern CPU_ONLINE = Pattern.compile(
            "^([0-9]+(-[0-9]+)?)(,[0-9]+(-[0-9]+)?)*$");

    // adb 客户端：所有采样命令都通过 adb shell 在设备上执行
    private final AdbClient adb;

    // Spring 构造注入 AdbClient（采样器无状态，单例复用）
    public PerfCollector(AdbClient adb) {
        this.adb = adb;
    }

    /** 一个采样点的快照。字段解析失败为 null（该行指标缺失，不阻断整点）。 */
    public record Sample(long tsMillis, Integer pssKb, Integer rssKb, Double cpuPercent,
                         Integer threadCount, Integer wsThreadCount, Integer netConnections,
                         Integer cpuCores,
                         // 以下 6 项为 meminfo「App Summary」分项（2026-09-28 新增，v1.2 #14）：
                         // 总 PSS 只能回答「涨了多少」，分项才能回答「是什么在涨」（定位泄漏类型）。
                         Integer javaHeapKb, Integer nativeHeapKb, Integer graphicsKb,
                         Integer codeKb, Integer privateOtherKb, Integer systemKb, Quality quality) {
        public Sample(long tsMillis,Integer pssKb,Integer rssKb,Double cpuPercent,Integer threadCount,Integer wsThreadCount,Integer netConnections,Integer cpuCores,Integer javaHeapKb,Integer nativeHeapKb,Integer graphicsKb,Integer codeKb,Integer privateOtherKb,Integer systemKb){this(tsMillis,pssKb,rssKb,cpuPercent,threadCount,wsThreadCount,netConnections,cpuCores,javaHeapKb,nativeHeapKb,graphicsKb,codeKb,privateOtherKb,systemKb,null);}
        public Sample timed(long offsetMs,Long intervalMs){Quality q=quality==null?new Quality(1,null,null,offsetMs,intervalMs,null,Map.of("legacy",new Source("UNKNOWN","UNKNOWN","未记录分源状态"))):new Quality(quality.version(),quality.pid(),quality.durationMs(),offsetMs,intervalMs,quality.processStartTicks(),quality.sources());return withQuality(q);}
        public Sample withQuality(Quality q){return new Sample(tsMillis,pssKb,rssKb,cpuPercent,threadCount,wsThreadCount,netConnections,cpuCores,javaHeapKb,nativeHeapKb,graphicsKb,codeKb,privateOtherKb,systemKb,q);}

        // tsMillis=采样时刻（毫秒时间戳）；pssKb=PSS（kB）；rssKb=RSS（kB）；
        // cpuPercent=CPU 占用率（%，top 单核口径）；cpuCores=在线核数（/sys/devices/system/cpu/online，
        // 2026-10-03 新增，v1.5 #21：单核值 ÷ 核数 = 整机口径，分母必须跟采样点走）；
        // threadCount=进程总线程数；wsThreadCount=WebSocket 线程数；
        // netConnections=该 app uid 的 ESTABLISHED TCP 连接数（/proc/net/tcp 网络层证据，方案 C）；
        // javaHeapKb=Java/Dalvik 堆 PSS；nativeHeapKb=Native 堆 PSS；graphicsKb=图形内存 PSS；
        // codeKb=代码段（.so/.dex/.apk/.art 等）PSS；privateOtherKb=私有其它 PSS；systemKb=系统共享库分摊 PSS
    }

    /** 取 app 进程 pid；进程不存在返回 null。 */
    public Integer pidOf(String serial, String pkg) {
        // 执行 adb shell pidof 取进程号；trim 去掉输出首尾空白
        String out = adb.shell(serial, "pidof " + pkg, 10).out().trim();
        if (out.isEmpty()) {
            // 无输出 = 进程不存在（app 未启动/已退出）
            return null;
        }
        try {
            // pidof 可能返回多个 pid（多进程应用），取第一个
            return Integer.parseInt(out.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return null; // 输出不是数字（异常输出）：同样视为不存在
        }
    }

    public record Source(String status,String source,String reason) {}
    public record Quality(int version,Integer pid,Long durationMs,Long offsetMs,Long intervalMs,Long processStartTicks,Map<String,Source> sources) {}
    public record ProcessProbe(String state) {}
    public ProcessProbe probeProcess(String serial,String pkg,int expectedPid){return probeProcess(serial,pkg,expectedPid,5);}
    public ProcessProbe probeProcess(String serial,String pkg,int expectedPid,long timeoutSeconds){
        if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))return new ProcessProbe("UNKNOWN");
        try{var result=adb.shell(serial,"pidof "+pkg,Math.max(1,Math.min(5,timeoutSeconds)));String out=result.out().trim();if(out.isEmpty())return new ProcessProbe(result.exitCode()==1?"ABSENT":"UNKNOWN");if(!result.ok()||!out.matches("[0-9]+(?:\\s+[0-9]+)*"))return new ProcessProbe("UNKNOWN");for(String part:out.split("\\s+"))if(Integer.parseInt(part)==expectedPid)return new ProcessProbe("PRESENT");return new ProcessProbe("DIFFERENT");}catch(Exception e){return new ProcessProbe("UNKNOWN");}
    }
    public Sample sample(String serial,int pid){return sample(serial,pid,20);}
    /** 各段输出独立边界/退出码；stderr只分类，不持久化设备路径或原始敏感内容。 */
    public Sample sample(String serial,int pid,long timeoutSeconds){
        if(pid<1)throw new IllegalArgumentException("无效进程号");
        String prefix="sp_section(){ echo __SP_BEGIN_$1; sp_name=$1; shift; \"$@\" 2>&1; sp_rc=$?; echo __SP_END_${sp_name}_$sp_rc; }; ";
        String cmd=prefix+"sp_section processStart cat /proc/"+pid+"/stat; sp_section meminfo dumpsys meminfo "+pid+"; sp_section status cat /proc/"+pid+"/status; sp_section top top -b -n 1; sp_section threads sh -c 'cat /proc/"+pid+"/task/*/comm'; sp_section cores cat /sys/devices/system/cpu/online; sp_section tcp sh -c 'cat /proc/net/tcp /proc/net/tcp6'; sp_section process cat /proc/"+pid+"/stat";
        long begin=System.nanoTime();
        try{var result=adb.shell(serial,cmd,Math.max(1,Math.min(20,timeoutSeconds)));return parseFramed(System.currentTimeMillis(),result.out(),pid,(System.nanoTime()-begin)/1000000,result.ok());}
        catch(Exception failure){return missing(pid,"采样命令失败或超时",(System.nanoTime()-begin)/1000000);}
    }
    public Sample missing(int pid,String reason,long durationMs){return new Sample(System.currentTimeMillis(),null,null,null,null,null,null,null,null,null,null,null,null,null,new Quality(1,pid,durationMs,null,null,null,Map.of("process",new Source("MISSING","PIDOF",reason))));}
    Sample parseFramed(long ts,String text,int pid,long durationMs,boolean adbOk){
        var sections=new LinkedHashMap<String,StringBuilder>();var codes=new HashMap<String,Integer>();String current=null;
        for(String line:text.split("\n")){if(current==null&&line.startsWith("__SP_BEGIN_")){current=line.substring(11).trim();sections.put(current,new StringBuilder());}else if(current!=null&&line.startsWith("__SP_END_"+current+"_")){int split=line.lastIndexOf('_');try{codes.put(current,Integer.parseInt(line.substring(split+1).trim()));}catch(Exception ignored){}current=null;}else if(current!=null)sections.get(current).append(line).append('\n');}
        var sources=new LinkedHashMap<String,Source>();var data=new HashMap<String,String>();
        for(String name:List.of("meminfo","status","top","threads","cores","tcp","processStart","process")){String part=sections.getOrDefault(name,new StringBuilder()).toString();String status=!adbOk||!codes.containsKey(name)?"MISSING":part.toLowerCase(Locale.ROOT).contains("permission denied")||part.toLowerCase(Locale.ROOT).contains("not permitted")?"DENIED":codes.get(name)!=0?"ERROR":part.isBlank()?"MISSING":"OK";sources.put(name,new Source(status,name.toUpperCase(Locale.ROOT),"OK".equals(status)?null:"DENIED".equals(status)?"权限拒绝":"未取得完整可读输出"));data.put(name,"OK".equals(status)?part:"");}
        String top=data.get("top").lines().filter(line->line.trim().startsWith(pid+" ")).collect(java.util.stream.Collectors.joining("\n"));
        Sample memory=parse(ts,data.get("meminfo"),pid),statusMetrics=parse(ts,data.get("status"),pid),cpuMetrics=parse(ts,top,pid);
        Integer cores=CPU_ONLINE.matcher(data.get("cores").trim()).matches()?parseCpuOnline(data.get("cores").trim()):null;
        Integer connections=completeTcpCount(data.get("tcp"),data.get("status"));
        if("OK".equals(sources.get("tcp").status())&&connections==null)
            sources.put("tcp",new Source("INCOMPLETE","PROC_NET_TCP_UID","连接表或UID未完整核对"));
        Sample parsed=new Sample(ts,memory.pssKb(),memory.rssKb()!=null?memory.rssKb():statusMetrics.rssKb(),cpuMetrics.cpuPercent(),statusMetrics.threadCount(),null,connections,cores,memory.javaHeapKb(),memory.nativeHeapKb(),memory.graphicsKb(),memory.codeKb(),memory.privateOtherKb(),memory.systemKb());
        Integer ws=null,net=parsed.netConnections();String threadData=data.get("threads");
        // task目录读到一部分也不可把部分名单当成全量0；退出码、声明线程数和实际行数必须可核对。
        long names=threadData.lines().filter(line->!line.isBlank()).count();
        if("OK".equals(sources.get("threads").status())&&names>0&&parsed.threadCount()!=null&&names==parsed.threadCount())ws=(int)threadData.lines().filter(line->WS_NAME.matcher(line.trim()).matches()).count();
        else if("OK".equals(sources.get("threads").status()))sources.put("threads",new Source("INCOMPLETE","PROC_TASK_COMM","线程名单与声明数量不一致或总数未知"));
        if(!"OK".equals(sources.get("tcp").status())||!data.get("status").contains("Uid:"))net=null;
        sources.put("pss",new Source(parsed.pssKb()==null?"MISSING":"OK","MEMINFO",parsed.pssKb()==null?"PSS字段未知":null));
        sources.put("rss",new Source(parsed.rssKb()==null?"MISSING":"OK",MEMINFO_RSS.matcher(data.get("meminfo")).find()?"MEMINFO_TOTAL_RSS":"PROC_STATUS_VM_RSS",parsed.rssKb()==null?"RSS字段未知":null));
        sources.put("cpu",new Source(parsed.cpuPercent()==null?"MISSING":"OK","TOP_ONE_CORE",parsed.cpuPercent()==null?"CPU字段未知":null));
        sources.put("ws",new Source(ws==null?"MISSING":"OK","PROC_TASK_COMM",ws==null?"完整线程计数依据不足":null));
        sources.put("net",new Source(net==null?"MISSING":"OK","PROC_NET_TCP_UID",net==null?"连接表或UID未知":null));
        Long ticks=null;String stat=data.get("process");try{int end=stat.lastIndexOf(')');if(end>0){String[] fields=stat.substring(end+1).trim().split("\\s+");if(fields.length>19)ticks=Long.valueOf(fields[19]);}}catch(Exception ignored){}
        Long beforeTicks=processTicks(data.get("processStart"));
        if(beforeTicks!=null&&ticks!=null&&!beforeTicks.equals(ticks))sources.put("process",new Source("CHANGED","PROC_STAT","采样命令期间进程启动时间变化"));else if(beforeTicks==null||ticks==null)sources.put("process",new Source("INCOMPLETE","PROC_STAT","采样前后进程启动时间未完整核对"));
        return new Sample(ts,parsed.pssKb(),parsed.rssKb(),parsed.cpuPercent(),parsed.threadCount(),ws,net,parsed.cpuCores(),parsed.javaHeapKb(),parsed.nativeHeapKb(),parsed.graphicsKb(),parsed.codeKb(),parsed.privateOtherKb(),parsed.systemKb(),new Quality(1,pid,durationMs,null,null,ticks,sources));
    }

    private static Long processTicks(String stat){try{int end=stat.lastIndexOf(')');String[] fields=stat.substring(end+1).trim().split("\\s+");return end>0&&fields.length>19?Long.valueOf(fields[19]):null;}catch(Exception e){return null;}}

    /** 表头可读但数据行残缺，也不能把“没有成功解析的连接”记成实际零。 */
    private static Integer completeTcpCount(String table,String status){
        Integer uid=null;
        for(String line:status.lines().toList()){
            var match=UID_LINE.matcher(line.trim());
            if(match.find())try{uid=Integer.valueOf(match.group(1));}catch(NumberFormatException invalid){return null;}
        }
        if(uid==null)return null;
        boolean header=false;int count=0;
        for(String line:table.lines().toList()){
            String text=line.trim();if(text.isEmpty())continue;
            String[] fields=text.split("\\s+");
            if("sl".equals(fields[0])){
                var names=Arrays.asList(fields);
                if(fields.length<10||!names.contains("local_address")
                        ||!(names.contains("rem_address")||names.contains("remote_address"))
                        ||!names.contains("st")||!names.contains("uid"))return null;
                header=true;continue;
            }
            if(!header||fields.length<10||!fields[0].matches("[0-9]+:")
                    ||!fields[1].matches("(?i)(?:[0-9a-f]{8}|[0-9a-f]{32}):[0-9a-f]{4}")
                    ||!fields[2].matches("(?i)(?:[0-9a-f]{8}|[0-9a-f]{32}):[0-9a-f]{4}")
                    ||!fields[3].matches("(?i)[0-9a-f]{2}"))return null;
            try{if(Integer.parseInt(fields[7])==uid&&"01".equals(fields[3]))count++;}
            catch(NumberFormatException invalid){return null;}
        }
        return header?count:null;
    }

    /** 解析 adb 原始输出为一个采样点。各指标独立解析互不影响：某行缺失/格式异常只影响对应字段（置 null），不阻断整点。
     * @param ts 采样时间戳（毫秒）
     * @param out adb shell 返回的原始输出（多行）
     * @param pid 被测进程号（用于识别 top 行）
     * @return 组装好的采样点 */
    Sample parse(long ts, String out, int pid) {
        // 三个可缺失的指标初始为 null（PSS/RSS/线程数）；CPU 可能因 top 行未出现而缺失
        Integer pss = null, rss = null, threadCount = null;
        Double cpu = null;
        int wsCount = 0; // WebSocket 线程数从 0 累加（线程名列表逐行匹配）
        Integer appUid = null; // 被测 app 的 uid（/proc/net/tcp 按 uid 过滤连接数）
        Integer netConns = null; // ESTABLISHED TCP 连接数（网络层证据，方案 C）
        Integer cpuCores = null; // 在线核数（v1.5 #21：整机口径分母，÷ 采样点单核值得整机占用）
        // meminfo App Summary 的六个分项（解析不到保持 null，不阻断整点）
        Integer javaHeap = null, nativeHeap = null, graphics = null;
        Integer code = null, privateOther = null, system = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            Matcher m;
            // 逐行分类匹配：每行只归属一种指标
            if ((m = PSS.matcher(t)).find()) {
                // 命中 TOTAL PSS：捕获的数字即 PSS（kB）
                pss = Integer.parseInt(m.group(1));
                // 部分系统把 PSS 与 RSS 放在同一行；需在本分支内继续匹配 RSS。
                // 否则 else-if 会被短路，只能得到主进程 VmRSS 兜底口径。
                Matcher rm = MEMINFO_RSS.matcher(t);
                if (rm.find()) {
                    rss = Integer.parseInt(rm.group(1));
                }
            } else if (pss == null && (m = PSS_TOTAL.matcher(t)).find()) {
                // 兼容老格式 TOTAL:（无 PSS 字样）：仅在 TOTAL PSS 缺失时回退
                pss = Integer.parseInt(m.group(1));
            } else if ((m = MEMINFO_RSS.matcher(t)).find()) {
                // 兜底：个别 ROM 把 TOTAL RSS 单独成行时仍能取到
                rss = Integer.parseInt(m.group(1));
            } else if ((m = VM_RSS.matcher(t)).find()) {
                if (rss == null) { // meminfo TOTAL RSS 优先，/proc VmRSS 兜底
                    rss = Integer.parseInt(m.group(1));
                }
            } else if ((m = THREADS.matcher(t)).find()) {
                // 命中 Threads：进程总线程数
                threadCount = Integer.parseInt(m.group(1));
            } else if ((m = UID_LINE.matcher(t)).find()) {
                // 命中 Uid 行：app 的 uid（/proc/net/tcp 连接归属用）
                appUid = Integer.parseInt(m.group(1));
            } else if ((m = SUMMARY_ITEM.matcher(t)).find()) {
                // meminfo App Summary 分项（2026-09-28 新增，v1.2 #14）：
                // 标签后的第一个数字即 Pss(KB)，按标签分发到对应字段。
                // 只认带冒号的标签，因此不会命中上方明细表的同名行（那边写作 "  Native Heap    34706 ..."）。
                int v = Integer.parseInt(m.group(2));
                switch (m.group(1)) {
                    case "Java Heap" -> javaHeap = v;
                    case "Native Heap" -> nativeHeap = v;
                    case "Graphics" -> graphics = v;
                    case "Code" -> code = v;
                    case "Private Other" -> privateOther = v;
                    case "System" -> system = v;
                    // Stack：数值小且与泄漏定位关系弱，不单独存储（明细表里仍可人工查）
                    default -> {
                    }
                }
            } else if (netConns == null && t.startsWith("sl")) {
                // /proc/net/tcp（或 tcp6）表头：首次遇到时开始统计连接数
                netConns = 0;
            } else if (netConns != null && appUid != null) {
                // tcp/tcp6 数据行：格式 sl local rem st tx rx tr tm->when retrnsmt uid timeout inode ...
                // split 后 tokens[3]=st（01=ESTABLISHED），tokens[7]=uid
                String[] f = t.split("\\s+");
                if (f.length >= 8 && "01".equals(f[3])) {
                    try {
                        if (Integer.parseInt(f[7]) == appUid) {
                            netConns++;
                        }
                    } catch (NumberFormatException ignore) {
                        // 非数字行（异常输出）忽略
                    }
                }
            } else if (WS_NAME.matcher(t).matches()) {
                // 线程名含 websocket（忽略大小写）：WebSocket 线程计数 +1
                wsCount++;
            } else if (cpuCores == null && CPU_ONLINE.matcher(t).matches()) {
                // /sys/devices/system/cpu/online 输出（v1.5 #21）："0-7" 或 "0-3,5-7"。
                // 放在链条靠后：前面所有指标都有自己的行特征，纯数字区间行只会是核数输出。
                // 只取第一次成功解析（单圈内极少变化；真变了也只影响该点的整机换算精度）
                cpuCores = parseCpuOnline(t);
            } else if (t.startsWith(String.valueOf(pid) + " ")) {
                // 以"pid + 空格"开头的行是 top 输出的进程行（top 输出首列即 pid）
                // top 行：按空白分列，%CPU 是第 9 列（index 8）
                String[] f = t.split("\\s+");
                if (f.length >= 9) {
                    // 列数足够才取第 9 列，防止匹配到残缺行
                    try {
                        double value=Double.parseDouble(f[8]);cpu=Double.isFinite(value)&&value>=0?value:null;
                    } catch (NumberFormatException ignore) {
                        // 该点 CPU 缺失
                    }
                }
            }
        }
        // 组装采样点：ts 为外部传入的采样时刻；末尾 6 项为 meminfo App Summary 分项（可能为 null）
        return new Sample(ts, pss, rss, cpu, threadCount, wsCount>0?wsCount:null, netConns, cpuCores,
                javaHeap, nativeHeap, graphics, code, privateOther, system);
    }

    /** 解析 /sys/devices/system/cpu/online 的内容为在线核数："0-7"→8、"0-3,5-7"→7、"0"→1；解析失败返回 null。 */
    static Integer parseCpuOnline(String line) {
        try {
            int cores = 0;
            // 逗号分隔的每一段是 "a"（单核）或 "a-b"（区间，含两端）
            for (String seg : line.trim().split(",")) {
                int dash = seg.indexOf('-');
                if (dash < 0) {
                    cores += 1;
                } else {
                    cores += Integer.parseInt(seg.substring(dash + 1)) - Integer.parseInt(seg.substring(0, dash)) + 1;
                }
            }
            return cores > 0 ? cores : null;
        } catch (Exception e) {
            return null; // 格式异常：该点无核数，整机口径该点缺失，不阻断整点
        }
    }
}
