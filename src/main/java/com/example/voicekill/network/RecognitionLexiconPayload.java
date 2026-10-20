package com.example.voicekill.network;

import com.example.voicekill.speech.WordMatcher;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;

/** 可选的本轮识别词表：不发送别名，不更改旧 HUD 协议。 */
public record RecognitionLexiconPayload(List<String> words,List<Boolean> preferred) implements CustomPacketPayload {
    public static final Type<RecognitionLexiconPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("voicekill","recognition_lexicon"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RecognitionLexiconPayload> CODEC=StreamCodec.of(RecognitionLexiconPayload::encode,RecognitionLexiconPayload::decode);
    public RecognitionLexiconPayload {
        words=List.copyOf(words);preferred=List.copyOf(preferred);
        if(words.size()>20||words.size()!=preferred.size())throw new IllegalArgumentException("无效识别词表");
    }
    public static RecognitionLexiconPayload of(List<WordMatcher.Word> words) {
        var limited=words.stream().limit(20).toList();
        return new RecognitionLexiconPayload(limited.stream().map(WordMatcher.Word::text).toList(),limited.stream().map(WordMatcher.Word::preferred).toList());
    }
    private static void encode(RegistryFriendlyByteBuf buf,RecognitionLexiconPayload p) {
        buf.writeVarInt(p.words.size());
        for(int i=0;i<p.words.size();i++){buf.writeUtf(p.words.get(i),64);buf.writeBoolean(p.preferred.get(i));}
    }
    private static RecognitionLexiconPayload decode(RegistryFriendlyByteBuf buf) {
        int count=buf.readVarInt();if(count<0||count>20)throw new IllegalArgumentException("识别词表超限");
        List<String> words=new ArrayList<>();List<Boolean> preferred=new ArrayList<>();
        for(int i=0;i<count;i++){words.add(buf.readUtf(64));preferred.add(buf.readBoolean());}
        return new RecognitionLexiconPayload(words,preferred);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
