package com.sudpanel.adb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sudpanel.config.AppProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;

/** The configured executable is always a temporary fake; no real adb binary or connected device is used. */
@Timeout(15)
class AdbStartAppTest {
    @TempDir Path directory;
    static final String SERIAL="fictional-device",PKG="com.fictional.game";
    AdbClient client;Path commands,resolveArgs,launchArgs;
    @BeforeEach void setup()throws Exception{
        commands=directory.resolve("commands".strip());resolveArgs=directory.resolve("resolve-args");launchArgs=directory.resolve("launch-args");
        output("resolve-out",PKG+"/.MainActivity\n");output("resolve-err","");output("resolve-exit","0");output("launch-out","Starting: Intent {}\nStatus: ok\nComplete\n");output("launch-err","");output("launch-exit","0");
        Path executable=directory.resolve("fake-adb.sh");
        String body="""
                #!/bin/sh
                [ "$1" = '-s' ] && [ "$2" = 'fictional-device' ] && [ "$3" = 'shell' ] && [ "$#" -eq 4 ] || exit 96
                printf '%s\\n' "$4" >> COMMANDS
                cmd() { for arg in "$@"; do printf '%s\\n' "$arg"; done > RESOLVE_ARGS;cat RESOLVE_OUT;cat RESOLVE_ERR >&2;return "$(cat RESOLVE_EXIT)"; }
                am() { for arg in "$@"; do printf '%s\\n' "$arg"; done > LAUNCH_ARGS;cat LAUNCH_OUT;cat LAUNCH_ERR >&2;return "$(cat LAUNCH_EXIT)"; }
                Nested=WOULD_EXPAND_IF_UNQUOTED
                eval "$4"
                """;
        for(String name:List.of("commands","resolve-args","resolve-out","resolve-err","resolve-exit","launch-args","launch-out","launch-err","launch-exit"))body=body.replace(name.toUpperCase(Locale.ROOT).replace('-','_'),quote(directory.resolve(name).toString()));
        Files.writeString(executable,body);assertThat(executable.toFile().setExecutable(true)).isTrue();
        AppProperties properties=mock(AppProperties.class);when(properties.adbPath()).thenReturn(executable.toString());client=new AdbClient(properties);
    }
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    void output(String file,String value)throws Exception{Files.writeString(directory.resolve(file),value);}
    void clearJournal()throws Exception{Files.deleteIfExists(commands);Files.deleteIfExists(resolveArgs);Files.deleteIfExists(launchArgs);}
    void assertOnlyExplicitLaunchCommands()throws Exception{assertThat(Files.readString(commands)).doesNotContain("monkey","settings ","input ","freezeRotation","thawRotation","wm ");}
    @Test void realFakeRemoteShellPreservesNestedDollarComponentAndNeverTouchesRotation()throws Exception{
        String component=PKG+"/.Main$Nested";output("resolve-out","priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"+component+"\n");client.startApp(SERIAL,PKG);
        assertThat(Files.readAllLines(resolveArgs)).containsExactly("package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-p",PKG);
        assertThat(Files.readAllLines(launchArgs)).containsExactly("start","-W","-n",component,"-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER");assertThat(Files.readAllLines(commands)).hasSize(2);assertOnlyExplicitLaunchCommands();
    }
    @Test void fullActivityNameAndAlreadyRunningWarningAreSuccessfulWithStatusOk()throws Exception{
        output("resolve-out",PKG+"/com.fictional.library.Entry\n");output("launch-out","Warning: Activity not started, its current task has been brought to the front\nStatus: ok\nComplete\n");assertThatCode(()->client.startApp(SERIAL,PKG)).doesNotThrowAnyException();assertThat(Files.readAllLines(launchArgs)).contains(PKG+"/com.fictional.library.Entry");assertOnlyExplicitLaunchCommands();
    }
    @Test void unsafePackageIsRejectedBeforeAnyProcessStarts()throws Exception{
        for(String pkg:Arrays.asList(null,"","com..bad","com.bad;id","com.bad$(id)","com.bad\nother","com.bad'", "-p", "com.123")){
            assertThatThrownBy(()->client.startApp(SERIAL,pkg)).isInstanceOf(IllegalArgumentException.class);assertThat(commands).doesNotExist();
        }
    }
    @Test void resolverRejectsForeignAmbiguousMissingAndInjectedComponentsWithoutFallback()throws Exception{
        for(String text:List.of("com.other/.Main\n",PKG+"/.Main\n"+PKG+"/.Other\n","No activity found\n","",PKG+"/.Main; echo injected\n",PKG+"/.Main'bad\n",PKG+"/.Main\nError: resolver failed\n")){
            clearJournal();output("resolve-out",text);assertThatThrownBy(()->client.startApp(SERIAL,PKG)).isInstanceOf(IllegalStateException.class);assertThat(launchArgs).doesNotExist();assertThat(Files.readAllLines(commands)).hasSize(1);assertOnlyExplicitLaunchCommands();
        }
    }
    @Test void nonzeroAndZeroExitResolverErrorsAreExplicitFailures()throws Exception{
        output("resolve-exit","2");assertThatThrownBy(()->client.startApp(SERIAL,PKG)).hasMessageContaining("解析启动组件失败");assertThat(launchArgs).doesNotExist();
        output("resolve-exit","0");output("resolve-err","Error: package resolver rejected\n");assertThatThrownBy(()->client.startApp(SERIAL,PKG)).hasMessageContaining("Error: package resolver rejected");assertThat(launchArgs).doesNotExist();
    }
    @Test void zeroExitErrorExceptionTimeoutAndMissingStatusNeverLookSuccessful()throws Exception{
        for(String text:List.of("Error type 3\nError: Activity class does not exist\n","java.lang.SecurityException: Permission Denial\n","Exception occurred while executing 'start':\n","Status: timeout\n","Starting: Intent {}\n","Status: ok\nStatus: timeout\n","Status: ok\nError: rejected\n")){
            clearJournal();output("launch-out",text);assertThatThrownBy(()->client.startApp(SERIAL,PKG)).isInstanceOf(IllegalStateException.class).hasMessageContaining("启动应用");assertThat(Files.readAllLines(commands)).hasSize(2);assertOnlyExplicitLaunchCommands();
        }
        output("launch-out","Status: ok\n");output("launch-err","Error: stderr failure\n");assertThatThrownBy(()->client.startApp(SERIAL,PKG)).hasMessageContaining("stderr failure");
    }
    @Test void bothStagesHaveExplicitFiniteTimeoutsAndTimeoutStopsWithoutFallback(){
        AdbClient spy=spy(client);doReturn(new AdbClient.ExecResult(-1,"partial","timeout after 10000ms")).when(spy).shell(eq(SERIAL),anyString(),eq(10L));
        assertThatThrownBy(()->spy.startApp(SERIAL,PKG)).hasMessageContaining("解析启动组件失败","超时");verify(spy).shell(eq(SERIAL),startsWith("cmd package resolve-activity --brief "),eq(10L));verify(spy,never()).shell(eq(SERIAL),startsWith("am "),anyLong());
        reset(spy);doReturn(new AdbClient.ExecResult(0,PKG+"/.MainActivity\n","")).when(spy).shell(eq(SERIAL),anyString(),eq(10L));doReturn(new AdbClient.ExecResult(-1,"partial","timeout after 15000ms")).when(spy).shell(eq(SERIAL),anyString(),eq(15L));
        assertThatThrownBy(()->spy.startApp(SERIAL,PKG)).hasMessageContaining("启动应用失败","超时");verify(spy).shell(eq(SERIAL),startsWith("am start -W -n "),eq(15L));verify(spy,times(2)).shell(eq(SERIAL),anyString(),anyLong());
    }
}
