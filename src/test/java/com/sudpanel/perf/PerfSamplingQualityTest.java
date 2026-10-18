package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PerfSamplingQualityTest {
    String section(String key,int code,String data){return "__SP_BEGIN_"+key+"\n"+data+"\n__SP_END_"+key+"_"+code+"\n";}
    String stat(long ticks){return "42 (fake worker) S "+"0 ".repeat(18)+ticks+" 0";}
    String frame(String threads,int threadCode,String tcp){return section("processStart",0,stat(99))+section("meminfo",0,"TOTAL PSS: 1024 TOTAL RSS: 2048\nJava Heap: 500")+section("status",0,"VmRSS: 1024 kB\nThreads: 2\nUid: 10001")+section("top",0,"42 u 0 0 0 0 0 S 0.0 0 cmd")+section("threads",threadCode,threads)+section("cores",0,"0-7")+section("tcp",0,tcp)+section("process",0,stat(99));}
    PerfCollector collector(){return new PerfCollector(mock(AdbClient.class));}
    @Test void completeThreadListCanProveActualZeroButMissingOrDeniedCannot(){
        var zero=collector().parseFramed(1,frame("main\nRenderThread",0,"sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"),42,60,true);
        assertThat(zero.wsThreadCount()).isZero();assertThat(zero.cpuPercent()).isZero();assertThat(zero.netConnections()).isZero();assertThat(zero.quality().processStartTicks()).isEqualTo(99);
        var denied=collector().parseFramed(1,frame("cat: Permission denied",1,"sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"),42,60,true);
        assertThat(denied.wsThreadCount()).isNull();assertThat(denied.quality().sources().get("threads").status()).isEqualTo("DENIED");assertThat(denied.pssKb()).isEqualTo(1024);
        var empty=collector().parseFramed(1,frame("",0,""),42,60,true);assertThat(empty.wsThreadCount()).isNull();assertThat(empty.netConnections()).isNull();
        var partial=collector().parseFramed(1,frame("main",0,""),42,60,true);assertThat(partial.wsThreadCount()).isNull();assertThat(partial.quality().sources().get("threads").status()).isEqualTo("INCOMPLETE");
    }
    @Test void tcpZeroRequiresReadableCompleteTablesAndMalformedRowsRemainUnknown(){
        String header="sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode";
        String row="0: 0100007F:1234 0200007F:2345 01 00000000:00000000 00:00000000 00000000 10001 0 12345";
        var full=collector().parseFramed(1,frame("main\nRenderThread",0,header+"\n"+row+"\n"+row.replace("10001","10002")+"\n"+header.replace("rem_address","remote_address")+"\n"+row.replace("0100007F","00000000000000000000000001000000").replace("0200007F","00000000000000000000000002000000")),42,1,true);
        assertThat(full.netConnections()).isEqualTo(2);
        var empty=collector().parseFramed(1,frame("main\nRenderThread",0,header+"\n"+header),42,1,true);
        assertThat(empty.netConnections()).isZero();
        for(String malformed:List.of(header+"\n0: 0100007F:1234",header+"\n"+row.replace("10001","NOT_A_UID"),row,"sl local_address rem_address")){
            var bad=collector().parseFramed(1,frame("main\nRenderThread",0,malformed),42,1,true);
            assertThat(bad.netConnections()).as("unreadable or incomplete TCP table").isNull();
            assertThat(bad.quality().sources().get("tcp").status()).isEqualTo("INCOMPLETE");
            assertThat(bad.pssKb()).isEqualTo(1024);assertThat(bad.wsThreadCount()).isZero();
        }
    }
    @Test void permissionDiagnosticsDoNotPersistRawSensitivePathsAndTruncatedSectionsStayMissing(){
        String f=frame("main\nOkHttp",0,"sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode");var normal=collector().parseFramed(1,f,42,10,true);assertThat(normal.wsThreadCount()).isEqualTo(1);
        var broken=collector().parseFramed(1,section("meminfo",0,"TOTAL PSS: 100")+"__SP_BEGIN_threads\ncat: /private/SECRET-IDENTITY Permission denied",42,2,false);assertThat(broken.pssKb()).isNull();assertThat(broken.quality().toString()).doesNotContain("SECRET-IDENTITY");assertThat(broken.quality().sources().get("threads").status()).isEqualTo("MISSING");
        assertThat(collector().parse(1,"",42).wsThreadCount()).isNull();
        assertThat(collector().parse(1,"42 u 0 0 0 0 0 S NaN 0 cmd",42).cpuPercent()).isNull();
    }
    @Test void threadNamesCannotOverwriteMemoryThreadCountOrCoreSources(){
        String names="0\nTOTAL PSS: 0";
        var sampled=collector().parseFramed(1,frame(names,0,""),42,1,true);
        assertThat(sampled.pssKb()).isEqualTo(1024);assertThat(sampled.cpuCores()).isEqualTo(8);assertThat(sampled.threadCount()).isEqualTo(2);assertThat(sampled.wsThreadCount()).isZero();
        var boundaryLike=collector().parseFramed(1,frame("__SP_BEGIN_top\nRenderThread",0,""),42,1,true);assertThat(boundaryLike.cpuPercent()).isZero();assertThat(boundaryLike.wsThreadCount()).isZero();
    }
    @Test void processProbeDistinguishesDisappearanceTransportFailureAndConfirmedChange(){
        var adb=mock(AdbClient.class);var c=new PerfCollector(adb);
        when(adb.shell(anyString(),anyString(),anyLong())).thenReturn(new AdbClient.ExecResult(0,"77 42",""),new AdbClient.ExecResult(1,"",""),new AdbClient.ExecResult(124,"","timeout"),new AdbClient.ExecResult(0,"77",""));
        assertThat(c.probeProcess("fake","com.fake.app",42).state()).isEqualTo("PRESENT");assertThat(c.probeProcess("fake","com.fake.app",42).state()).isEqualTo("ABSENT");assertThat(c.probeProcess("fake","com.fake.app",42).state()).isEqualTo("UNKNOWN");assertThat(c.probeProcess("fake","com.fake.app",42).state()).isEqualTo("DIFFERENT");
    }
    @Test void confirmedRestartIsStickyWhileReadFailureCanRecoverAndSamePidReuseIsDetected(){
        var c=new PerfProcessContinuity();var sample=collector().parseFramed(1,frame("main\nOkHttp",0,""),42,1,true);
        assertThat(c.observe(new PerfCollector.ProcessProbe("PRESENT"),sample)).isTrue();assertThat(c.status()).isEqualTo("CONTINUOUS");
        assertThat(c.observe(new PerfCollector.ProcessProbe("UNKNOWN"),null)).isTrue();assertThat(c.observe(new PerfCollector.ProcessProbe("ABSENT"),null)).isTrue();assertThat(c.observe(new PerfCollector.ProcessProbe("PRESENT"),sample)).isTrue();assertThat(c.status()).isEqualTo("UNVERIFIED");
        assertThat(c.observe(new PerfCollector.ProcessProbe("DIFFERENT"),null)).isFalse();assertThat(c.observe(new PerfCollector.ProcessProbe("PRESENT"),sample)).isFalse();
        var reused=new PerfProcessContinuity();reused.observe(new PerfCollector.ProcessProbe("PRESENT"),sample);var other=sample.withQuality(new PerfCollector.Quality(1,42,1L,null,null,100L,Map.of()));assertThat(reused.observe(new PerfCollector.ProcessProbe("PRESENT"),other)).isFalse();
        var mid=collector().parseFramed(1,frame("main\nOkHttp",0,"").replace(section("process",0,stat(99)),section("process",0,stat(100))),42,1,true);assertThat(new PerfProcessContinuity().observe(new PerfCollector.ProcessProbe("PRESENT"),mid)).isFalse();
    }
    @Test void monotonicScheduleWaitsOnlyRemainingTimeAndSkipsBacklog(){
        var now=new AtomicLong(1000000);var s=new PerfSamplingSchedule(5000,1000,now::get);assertThat(s.delayMillis()).isZero();now.addAndGet(600000000);s.complete();assertThat(s.delayMillis()).isEqualTo(400);now.addAndGet(400000000);assertThat(s.delayMillis()).isZero();now.addAndGet(1600000000);s.complete();assertThat(s.delayMillis()).isEqualTo(400);assertThat(s.skippedSlots()).isEqualTo(1);now.addAndGet(2400000000L);assertThat(s.hasTime()).isFalse();assertThat(s.delayMillis()).isEqualTo(-1);
    }
    @Test void conditionsMissingAreUnknownAndThresholdsNeverChangeDevice(){
        var health=PerfDeviceHealth.parse(new AdbClient.ExecResult(0,"level: 80\ntemperature: 423\nUSB powered: true",""),new AdbClient.ExecResult(0,"Thermal Status: 3",""));assertThat(health.temperatureC()).isEqualTo(42.3);assertThat(health.powered()).isTrue();assertThat(health.thermalStatus()).isEqualTo(3);
        assertThat(PerfDeviceHealth.warnings(health,new PerfDeviceHealth.Policy(40.0,2))).hasSize(2);
        var missing=PerfDeviceHealth.parse(new AdbClient.ExecResult(0,"unknown ROM",""),new AdbClient.ExecResult(1,"permission denied",""));assertThat(missing.temperatureC()).isNull();assertThat(missing.thermalStatus()).isNull();assertThat(missing.powered()).isNull();assertThat(PerfDeviceHealth.warnings(missing,new PerfDeviceHealth.Policy(null,null))).hasSize(3);
        assertThatThrownBy(()->new PerfDeviceHealth.Policy(Double.NaN,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingRatesAndRealIntervalsDoNotInventZero(){
        var d=new PerfSamplingDiagnostics(null,new PerfDeviceHealth.Policy(null,null));assertThat(((Map<?,?>)((Map<?,?>)d.summary(null).get("missing")).get("pss")).get("percent")).isNull();
        var c=collector();d.sample(c.parseFramed(1,frame("main\nOkHttp",0,""),42,600,true).timed(0,null));d.sample(c.missing(42,"unavailable",700).timed(1500,1500L));d.completed(2300,1000,1);var result=d.summary(null);
        assertThat(((Map<?,?>)((Map<?,?>)result.get("missing")).get("pss")).get("percent")).isEqualTo(50.0);assertThat(result.get("actualIntervalAvgMs")).isEqualTo(1500.0);assertThat(result.get("acquisitionAvgMs")).isEqualTo(650.0);
    }
}
