package com.sudpanel.backup;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;
/** 修改请求与恢复stage共用门闩，stage等在途同步写完成后才落盘。 */
@Component
public class MaintenanceGate { final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true); }
