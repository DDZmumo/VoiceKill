package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.voicekill.speech.RecognitionHealth.Issue.*;

class RecognitionHealthTest {
    private RecognitionSilence timer(){var timer=new RecognitionSilence();timer.configure(new RecognitionSilence.Rules(true,5,2,1));return timer;}
    @Test void unavailableCaptureAndModelsCannotAccumulateSilenceDamage() {
        for(var issue:new RecognitionHealth.Issue[]{MICROPHONE,MODEL_LOADING,MODEL_FAILED,STALLED,UNKNOWN}) {
            var health=new RecognitionHealth();health.reset("game");var timer=timer();
            for(int tick=0;tick<1000;tick++){health.report("p","game",issue,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)),issue.name());}
        }
    }
    @Test void healthyHeartbeatIsNotSpeechAndDoesNotPreventTheNormalPenalty() {
        var health=new RecognitionHealth();health.reset("game");var timer=timer();
        for(int tick=0;tick<100;tick++){health.report("p","game",READY,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)));}
        assertTrue(timer.step("p",100,health.ready("p",100)));
    }
    @Test void expiredHeartbeatAndRecoveryNeverCarryOldDamageDebt() {
        var health=new RecognitionHealth();health.reset("game");var timer=timer();
        health.report("p","game",READY,0);assertFalse(timer.step("p",0,health.ready("p",0)));
        assertFalse(health.ready("p",81));assertFalse(timer.step("p",1000,health.ready("p",1000)));
        for(int tick=1001;tick<1101;tick++){health.report("p","game",READY,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)));}
        health.report("p","game",READY,1101);assertTrue(timer.step("p",1101,health.ready("p",1101)));
    }
    @Test void staleSessionsAndOnePlayersFailureCannotAffectAnotherPlayer() {
        var health=new RecognitionHealth();health.reset("a");health.report("one","a",READY,0);health.report("two","a",MODEL_FAILED,0);
        assertTrue(health.ready("one",1));assertFalse(health.ready("two",1));
        health.reset("b");assertFalse(health.report("one","a",READY,2));assertFalse(health.ready("one",2));
        assertTrue(health.report("one","b",READY,3));health.forget("one");assertFalse(health.ready("one",3));
        health.clear();assertFalse(health.report("one","b",READY,4));
    }
    @Test void bottomWarningsAreRateLimitedAndRecoveryClearsTheWarningSchedule() {
        var health=new RecognitionHealth();health.reset("a");
        assertTrue(health.noticeDue("p",0));assertFalse(health.noticeDue("p",199));assertTrue(health.noticeDue("p",200));
        health.report("p","a",READY,201);assertFalse(health.noticeDue("p",201));
        health.report("p","a",MODEL_FAILED,202);assertTrue(health.noticeDue("p",202));
        assertTrue(MODEL_FAILED.warning().contains("可能无法正常游戏"));
    }
}
