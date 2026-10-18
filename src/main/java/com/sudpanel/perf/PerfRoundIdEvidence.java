package com.sudpanel.perf;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** IDs 永远是字符串；仅唯一且边界完整时给出确定值，不从前缀或最后一条猜归属。 */
public final class PerfRoundIdEvidence {
    private static final Pattern ID=Pattern.compile("(?:gameRoundId|game_round_id)[\\\"']?\\s*[:=]\\s*[\\\"']?([A-Za-z0-9_.-]+)");
    private static final Pattern TIME=Pattern.compile("^(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?");
    private final LinkedHashSet<String> ids=new LinkedHashSet<>();
    private int matches, reads;
    private String firstMatchedAt,lastMatchedAt,firstLogTimestamp,lastLogTimestamp;
    private boolean overflow;
    public void readObserved() { reads++; }
    public void observe(String fresh) {
        if(fresh==null||fresh.isBlank()) return;
        String observed=Instant.now().toString();
        for(String line:fresh.replace("\\\"","\"").split("\\R")) {
            var matcher=ID.matcher(line);
            while(matcher.find()) {
                String id=matcher.group(1);
                if(id.length()>4096) {overflow=true;continue;}
                matches++;
                if(ids.size()<20||ids.contains(id)) ids.add(id); else overflow=true;
                if(firstMatchedAt==null) firstMatchedAt=observed;
                lastMatchedAt=observed;
                var timestamp=TIME.matcher(line);String logTime=timestamp.find()?timestamp.group():null;
                if(firstLogTimestamp==null) firstLogTimestamp=logTime;
                lastLogTimestamp=logTime;
            }
        }
    }
    public String reliableId(boolean incomplete) {return !incomplete&&!overflow&&ids.size()==1?ids.iterator().next():null;}
    public Map<String,Object> summary(boolean incomplete) {
        String state=overflow||ids.size()>1?"CONFLICT":incomplete?"INCOMPLETE":ids.isEmpty()?"MISSING":"UNIQUE";
        var out=new LinkedHashMap<String,Object>();out.put("state",state);out.put("source","LOGCAT");
        out.put("candidateIds",List.copyOf(ids));out.put("matchCount",matches);out.put("readCount",reads);
        out.put("firstMatchedAt",firstMatchedAt);out.put("lastMatchedAt",lastMatchedAt);
        out.put("firstLogTimestamp",firstLogTimestamp);out.put("lastLogTimestamp",lastLogTimestamp);
        out.put("timeBasis","matchedAt为主机观察时间，logTimestamp保留设备日志原时间，不推定时区");
        out.put("missingReason",overflow?"候选过多或ID过长，不能确定归属":ids.size()>1?"本圈新增日志命中不同ID，需核对":incomplete?"本圈日志边界缺失或读取不完整":ids.isEmpty()?(reads==0?"未读取本圈日志":"本圈日志未命中可识别ID字段"):null);
        return out;
    }
}
