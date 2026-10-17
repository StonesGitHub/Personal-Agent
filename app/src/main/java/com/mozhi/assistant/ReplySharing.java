package com.mozhi.assistant;

import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/** Sharing has its own explicit allowlist; never serialize a conversation or agent context. */
public final class ReplySharing {
    private ReplySharing(){}
    public static final int QUESTION_LIMIT=8000,ANSWER_LIMIT=24000;
    public static JSONObject pair(List<Core.Item> history,Core.Item reply){
        if(!"assistant".equals(reply.s("role"))||!"done".equals(reply.s("status")))throw new IllegalArgumentException("只能分享已完成的回答");
        int at=-1;for(int n=0;n<history.size();n++)if(history.get(n).id.equals(reply.id)&&history.get(n).parent.equals(reply.parent))at=n;
        if(at<0)throw new IllegalArgumentException("未找到这段问答");
        for(int n=at-1;n>=0;n--){Core.Item item=history.get(n);if(item.parent.equals(reply.parent)&&"user".equals(item.s("role")))
            return Core.obj("client_id",UUID.randomUUID().toString(),"question",item.s("text"),"answer",reply.s("text"));}
        throw new IllegalArgumentException("请先补充这条回答对应的问题");
    }
    public static JSONObject payload(String id,String question,String answer){
        if(id==null||!id.matches("[a-zA-Z0-9-]{16,64}"))throw new IllegalArgumentException("分享标识无效，请重新打开分享");
        question=question.trim();answer=answer.trim();
        if(question.isEmpty()||question.length()>QUESTION_LIMIT)throw new IllegalArgumentException("问题请保留1–8000字");
        if(answer.isEmpty()||answer.length()>ANSWER_LIMIT)throw new IllegalArgumentException("回答请保留1–24000字");
        return Core.obj("client_id",id,"question",question,"answer",answer);
    }
    public static String toggledFeedback(String current,String choice){
        if(!"like".equals(choice)&&!"dislike".equals(choice))throw new IllegalArgumentException("未知反馈");
        return choice.equals(current)?"":choice;
    }
}
