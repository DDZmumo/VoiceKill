package com.example.voicekill.speech;

import java.util.HashMap;
import java.util.Map;

/** A ready heartbeat permits play, but never counts as recognized speech. Server-thread only. */
public final class RecognitionHealth {
    public static final long LEASE_TICKS=80,NOTICE_TICKS=200,FRAME_TIMEOUT_NANOS=3_000_000_000L;
    public enum Issue {
        READY(""),MICROPHONE("麦克风可能出现问题"),MODEL_LOADING("模型尚未就绪"),
        MODEL_FAILED("模型可能出现问题"),STALLED("语音处理可能卡住"),UNKNOWN("语音状态未确认");
        private final String description;
        Issue(String description){this.description=description;}
        public String warning(){return description+"，可能无法正常游戏（已暂停识别/扣血）";}
    }
    private record Report(Issue issue,long tick) {}
    private final Map<String,Report> reports=new HashMap<>();
    private final Map<String,Long> notices=new HashMap<>();
    private String session="";
    public void reset(String session){clear();this.session=session;}
    public boolean report(String player,String session,Issue issue,long tick) {
        if(this.session.isEmpty()||!this.session.equals(session))return false;
        reports.put(player,new Report(issue,tick));return true;
    }
    public Issue issue(String player,long tick) {
        var report=reports.get(player);
        return report==null||tick-report.tick()>LEASE_TICKS?Issue.UNKNOWN:report.issue();
    }
    public boolean ready(String player,long tick){return issue(player,tick)==Issue.READY;}
    public boolean noticeDue(String player,long tick) {
        if(ready(player,tick)){notices.remove(player);return false;}
        Long previous=notices.get(player);
        if(previous!=null&&tick-previous<NOTICE_TICKS)return false;
        notices.put(player,tick);return true;
    }
    public void forget(String player){reports.remove(player);notices.remove(player);}
    public void clear(){reports.clear();notices.clear();session="";}
}
