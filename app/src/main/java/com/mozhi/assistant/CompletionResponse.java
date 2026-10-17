package com.mozhi.assistant;

import java.io.IOException;
import org.json.*;

/** Validates a non-streaming completion before it can become a reply or execute tools. */
final class CompletionResponse {
    static final String TRUNCATED = "\n\n［本次回答未完成，已保留生成的内容，可以继续追问。］";

    static JSONObject message(String raw) throws IOException, JSONException {
        JSONObject root=new JSONObject(raw);
        JSONArray choices=root.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IOException("模型没有返回回答，请重试或检查模型设置。");
        JSONObject choice=choices.getJSONObject(0),reply=choice.getJSONObject("message");
        String finish=choice.optString("finish_reason");
        if("content_filter".equals(finish))throw new IOException("模型服务没有返回可显示的回答，请调整问题后重试。");
        if("insufficient_system_resource".equals(finish)||"aborted".equals(finish))
            throw new IOException("模型服务中断了本次回答，原问题已保留，可以重试。");
        Object content=reply.opt("content");
        if(content!=null&&content!=JSONObject.NULL&&!(content instanceof String))
            throw new IOException("模型返回的正文格式不受支持，请更换兼容的模型。");
        String text=content instanceof String?((String)content).trim():"";
        Object toolValue=reply.opt("tool_calls");
        if(toolValue!=null&&toolValue!=JSONObject.NULL&&!(toolValue instanceof JSONArray))
            throw new IOException("模型返回的工具调用格式不完整，请重试。");
        JSONArray calls=reply.optJSONArray("tool_calls");
        boolean hasTools=calls!=null&&calls.length()>0;
        if("length".equals(finish)) {
            // Even syntactically valid arguments may be incomplete when generation was cut off.
            if(hasTools)throw new IOException("模型的工具调用尚未生成完整，原问题已保留，请重试。");
            if(text.isEmpty())throw new IOException("模型在生成正文前达到输出限制，未完成回答。请重试或更换模型。");
            reply.put("content",text+TRUNCATED);
        } else if(!hasTools&&text.isEmpty()) {
            throw new IOException("模型没有返回正文，原问题已保留，请重试或更换模型。");
        }
        // Preserve provider fields for subsequent tool rounds; reasoning is not a substitute for an answer.
        return reply;
    }

    private CompletionResponse(){}
}
