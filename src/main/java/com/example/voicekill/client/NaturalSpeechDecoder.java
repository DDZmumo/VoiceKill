package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** 声学 VAD 决定自然边界；完整语音统一双语转写。实时假设只用于显示。 */
public final class NaturalSpeechDecoder implements CaptureSession.Decoder {
    private final ZipformerDecoder transcriber;
    private final Vad vad;
    private final Supplier<List<String>> words;
    private List<String> vocabulary;
    private final float[] frame=new float[512];
    private final float[] history;
    private final UtteranceBudget watchdog;
    private long totalSamples,epoch;
    private int used;
    private int idleSamples;
    private long epochSamples;
    private boolean closed;
    private volatile String partial="";
    private float[] finalized;
    private CaptureSession.Boundary boundary=CaptureSession.Boundary.NONE;

    public NaturalSpeechDecoder(Path model,Path vadModel,int threads,Supplier<List<String>> words,ZipformerDecoder.Settings settings) {
        this(model,vadModel,threads,words,settings,null);
    }
    public NaturalSpeechDecoder(Path model,Path vadModel,int threads,Supplier<List<String>> words,ZipformerDecoder.Settings settings,Supplier<String> hintOverride) {
        this.words=words;this.vocabulary=List.copyOf(words.get());
        history=new float[(int)((settings.maxUtteranceSeconds()+5)*16000)];
        watchdog=new UtteranceBudget(settings.maxUtteranceSeconds());
        transcriber=new ZipformerDecoder(model,threads,words,settings,hintOverride);
        try {
            vad=new Vad(VadModelConfig.builder().setSampleRate(16000).setNumThreads(1).setDebug(false)
                    .setSileroVadModelConfig(SileroVadModelConfig.builder().setModel(vadModel.toString())
                            .setWindowSize(512).setThreshold(.5f).setMinSpeechDuration(.08f)
                            .setMinSilenceDuration(settings.endpointSilence()).setMaxSpeechDuration(settings.maxUtteranceSeconds()).build()).build());
        } catch(Throwable failure) {transcriber.close();throw failure;}
    }

    @Override public List<String> accept(byte[] pcm) {
        if(closed)throw new IllegalStateException("语音会话已关闭");
        boundary=CaptureSession.Boundary.NONE;finalized=null;
        List<String> current=List.copyOf(words.get());
        if(!current.equals(vocabulary)) {
            // 换轮/切服时保留原有隔离规则：旧段不得影响新词表。
            vad.reset();totalSamples=0;used=0;idleSamples=0;epochSamples=0;vocabulary=current;epoch++;
            transcriber.resetPreview();partial="";
        }
        float[] samples=AudioCapture.pcmSamples(pcm);
        epochSamples+=samples.length;
        for(float sample:samples)history[(int)(totalSamples++%history.length)]=sample;
        String hypothesis=transcriber.preview(pcm);
        for(int offset=0;offset<samples.length;) {
            int count=Math.min(frame.length-used,samples.length-offset);
            System.arraycopy(samples,offset,frame,used,count);used+=count;offset+=count;
            if(used==frame.length){vad.acceptWaveform(Arrays.copyOf(frame,frame.length));used=0;}
        }
        partial=vad.isSpeechDetected()?hypothesis:"";
        // 词表切换不能无限重置持续讲话兜底。至少保留当前词表的一秒上下文再强制结算。
        if(watchdog.advance(samples.length,vad.isSpeechDetected())&&vad.isSpeechDetected()&&epochSamples>=16000)vad.flush();
        if(vad.empty()) {
            if(!vad.isSpeechDetected()) {
                boundary=CaptureSession.Boundary.IDLE;
                idleSamples+=samples.length;
                if(idleSamples>=32000) {
                    double energy=0;for(float sample:samples)energy+=(double)sample*sample;
                    if(energy/Math.max(1,samples.length)<1e-5) {
                        transcriber.resetPreview();transcriber.seedPreview(slice(Math.max(0,totalSamples-4000),totalSamples));idleSamples=0;watchdog.reset();
                    }
                }
            } else idleSamples=0;
            return List.of();
        }
        var segment=vad.front();
        long start=Math.max(0,(long)segment.getStart()-4000);
        long end=Math.min(totalSamples,(long)segment.getStart()+segment.getSamples().length+4000);
        if(start<totalSamples-history.length)throw new IllegalStateException("自然语音段超出缓存上限");
        finalized=slice(start,end);
        long consumed=Math.max(0,Math.min(totalSamples,(long)segment.getStart()+segment.getSamples().length));
        vad.pop();
        // 保留实时流中的完整声学上下文；不裁剪重解码，不在句中短暂停顿时重置。
        List<String> result=transcriber.finishPreview();
        transcriber.resetPreview();
        transcriber.seedPreview(slice(Math.max(0,consumed),totalSamples));
        partial="";idleSamples=0;epochSamples=0;watchdog.reset();
        boundary=result.isEmpty()?CaptureSession.Boundary.DISCARD:CaptureSession.Boundary.FINAL;
        return result;
    }
    private float[] slice(long start,long end) {
        if(start<totalSamples-history.length||end<start)throw new IllegalStateException("音频上下文超出缓存上限");
        float[] result=new float[(int)(end-start)];
        for(int i=0;i<result.length;i++)result[i]=history[(int)((start+i)%history.length)];
        return result;
    }
    @Override public float[] utteranceSamples(){return finalized;}
    @Override public String partial(){return partial;}
    @Override public long epoch(){return epoch;}
    @Override public CaptureSession.Boundary boundary(){return boundary;}
    @Override public void close(){if(closed)return;closed=true;try{vad.release();}finally{transcriber.close();}}
}
