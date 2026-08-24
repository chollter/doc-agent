package com.gcll.ticketagent.tool.function;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 值班通知工具（WRITE 级）——通过真实邮件发送通知。
 *
 * <p>场景：排查过程中需要人工介入（如证据指向需重启服务、需确认变更），
 * Agent 调用此工具发送邮件给值班人，触发人工关注。
 *
 * <p>治理：
 * <ul>
 *   <li>风险级别：WRITE——有副作用（发出邮件），但不需人工确认（通知不是处置）</li>
 *   <li>不重试：邮件发送失败不重试（避免轰炸收件人），返回失败结果供 Agent 决策</li>
 *   <li>审计：ToolResult 记录收件人+主题，供 Trace 追溯</li>
 * </ul>
 *
 * <p>收件人地址由 LLM 参数传入（通过 extract 字段或工具参数合并），
 * 若未传入则用配置的默认值班邮箱 {@code opsmind.notify.oncall-default-recipient}。
 */
@Component
@Profile("mail")
@ConditionalOnProperty(prefix = "opsmind.notify", name = "email-enabled", havingValue = "true")
public class NotifyOncallTool implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(NotifyOncallTool.class);
    private static final String TOOL_NAME = "notifyOncall";

    private final JavaMailSender mailSender;
    private final String defaultRecipient;

    @Autowired
    public NotifyOncallTool(
            JavaMailSender mailSender,
            @Value("${opsmind.notify.oncall-default-recipient:oncall@company.com}") String defaultRecipient
    ) {
        this.mailSender = mailSender;
        this.defaultRecipient = defaultRecipient;
    }

    @Override
    public ToolType toolType() {
        return ToolType.WRITE_FUNCTION;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();

        String recipient = resolveRecipient(extract);
        String subject = buildSubject(extract);
        String body = buildBody(extract, originalContent);

        String input = "recipient=" + recipient + ",subject=" + subject;

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);

            String output = "邮件已发送给值班人 " + recipient;
            log.info("Oncall notification sent to={}, subject={}", recipient, subject);
            return ToolResult.success(ToolType.WRITE_FUNCTION, TOOL_NAME, input, output,
                    System.currentTimeMillis() - start);

        } catch (Exception ex) {
            log.error("Oncall notification failed, recipient={}, error={}", recipient, ex.getMessage());
            return ToolResult.failure(ToolType.WRITE_FUNCTION, TOOL_NAME, input,
                    "邮件发送失败: " + ex.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private String resolveRecipient(TicketExtractResult extract) {
        // 工单未指定收件人时用默认值班邮箱
        // 未来可从 extract 扩展字段或 LLM 参数传入具体收件人
        return defaultRecipient;
    }

    private String buildSubject(TicketExtractResult extract) {
        String system = extract.affectedSystem() != null ? extract.affectedSystem() : "未知系统";
        String module = extract.affectedModule() != null ? extract.affectedModule() : "";
        return "【运维告警】" + system + (module.isEmpty() ? "" : " - " + module) + " 需人工介入";
    }

    private String buildBody(TicketExtractResult extract, String originalContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("运维 Agent 在排查过程中发现需要人工介入。\n\n");
        sb.append("=== 工单信息 ===\n");
        sb.append("问题类型: ").append(extract.issueType()).append("\n");
        sb.append("受影响系统: ").append(extract.affectedSystem() != null ? extract.affectedSystem() : "待确认").append("\n");
        sb.append("受影响模块: ").append(extract.affectedModule() != null ? extract.affectedModule() : "待确认").append("\n");
        sb.append("错误码: ").append(extract.errorCode() != null ? extract.errorCode() : "无").append("\n");
        sb.append("环境: ").append(extract.environment() != null ? extract.environment() : "未知").append("\n");
        sb.append("影响范围: ").append(extract.impactScope() != null ? extract.impactScope() : "待评估").append("\n\n");
        sb.append("=== 工单原文 ===\n");
        sb.append(originalContent != null ? originalContent : "(无原文)").append("\n");
        return sb.toString();
    }
}
